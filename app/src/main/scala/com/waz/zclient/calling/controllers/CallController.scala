/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.calling.controllers

import android.media.AudioManager
import android.os.{PowerManager, Vibrator}
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.Verification
import com.waz.avs.{VideoPreview, VideoRenderer}
import com.waz.model.{AssetId, UserData, UserId}
import com.waz.service.call.Avs.VideoState
import com.waz.service.call.CallInfo
import com.waz.service.call.CallInfo.CallState.{SelfJoining, _}
import com.waz.service.{AccountsService, GlobalModule, NetworkModeService, ZMessaging}
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.events._
import com.waz.zclient.calling.CallingActivity
import com.waz.zclient.calling.controllers.CallController.CallParticipantInfo
import com.waz.zclient.common.controllers.ThemeController.Theme
import com.waz.zclient.common.controllers.{SoundController, ThemeController}
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.{ConversationMembersSignal, DeprecationUtils, LayoutSpec, UiStorage, UserSignal}
import com.waz.zclient.{Injectable, Injector, R, WireContext}

import scala.concurrent.duration._

class CallController(implicit inj: Injector, cxt: WireContext, eventContext: EventContext) extends Injectable {

  import Threading.Implicits.Background
  import VideoState._
  private implicit val uiStorage: UiStorage = inject[UiStorage]

  private val screenManager  = new ScreenManager
  val soundController        = inject[SoundController]
  val conversationController = inject[ConversationController]
  val networkMode            = inject[NetworkModeService].networkMode
  val accounts               = inject[AccountsService]
  val themeController        = inject[ThemeController]

  //The zms of the account that's currently active (if any)
  val activeZmsOpt = inject[Signal[Option[ZMessaging]]]

  val callControlsVisible = Signal(false)
  //the zms of the account that currently has an active call (if any)
  val callingZmsOpt =
    for {
      acc <- inject[GlobalModule].calling.activeAccount
      zms <- acc.fold(Signal.const(Option.empty[ZMessaging]))(id => Signal.future(ZMessaging.currentAccounts.getZms(id)))
    } yield zms
  val callingZms = callingZmsOpt.collect { case Some(z) => z }

  val currentCallOpt: Signal[Option[CallInfo]] = callingZmsOpt.flatMap {
    case Some(z) => z.calling.currentCall
    case _ => Signal.const(None)
  }
  val currentCall = currentCallOpt.collect { case Some(c) => c }

  val callConvIdOpt     = currentCallOpt.map(_.map(_.convId))
  val callConvId        = callConvIdOpt.collect { case Some(c) => c }

  val isCallActive      = currentCallOpt.map(_.isDefined)
  val isCallActiveDelay = isCallActive.flatMap {
    case true  => Signal.future(CancellableFuture.delay(300.millis).future.map(_ => true)).orElse(Signal.const(false))
    case false => Signal.const(false)
  }

  val callStateOpt      = currentCallOpt.map(_.flatMap(_.state))
  val callState         = callStateOpt.collect { case Some(s) => s }

  val prevCallStateOpt = currentCallOpt.map(_.flatMap(_.prevState))

  val isCallEstablished = callStateOpt.map(_.contains(SelfConnected))
  val isCallOutgoing    = callStateOpt.map(_.contains(SelfCalling))
  val isCallIncoming    = callStateOpt.map(_.contains(OtherCalling))

  val isMuted           = currentCall.map(_.muted)
  val startedAsVideo    = currentCall.map(_.startedAsVideoCall)


  val videoSendState    = currentCall.map(_.videoSendState)
  val videoReceiveStates = currentCall.map(_.videoReceiveState)
  val allVideoReceiveStates = Signal(callingZms.map(_.selfUserId), videoReceiveStates, videoSendState).map {
    case (selfId, others, self) => others.updated(selfId, self)
  }
  val isVideoCall       = allVideoReceiveStates.map(_.exists(_._2 != VideoState.Stopped))
  val isGroupCall       = currentCall.map(_.isGroup)
  val cbrEnabled        = currentCall.map(_.isCbrEnabled)
  val duration          = currentCall.flatMap(_.durationFormatted)
  val otherUserId       = currentCall.map(_.others.headOption)

  val participantIds = currentCall.map(_.others.toVector)

  val theme: Signal[Theme] = isVideoCall.flatMap {
    case true => Signal.const(Theme.Dark)
    case false => themeController.currentTheme
  }

  def participantInfos(take: Option[Int] = None) =
    for {
      ids         <- take.fold(participantIds)(t => participantIds.map(_.take(t)))
      videoStates <- allVideoReceiveStates
      users       <- Signal.sequence(ids.map(UserSignal(_)):_*)
      teamId      <- callingZms.map(_.teamId)
    } yield
      users.map { u =>
        CallParticipantInfo(
          u.id,
          u.picture,
          u.displayName,
          u.isGuest(teamId),
          u.isVerified,
          videoStates.get(u.id).contains(VideoState.Started))
      }

   val flowManager = callingZms.map(_.flowmanager)

  val captureDevices = flowManager.flatMap(fm => Signal.future(fm.getVideoCaptureDevices))

  //TODO when I have a proper field for front camera, make sure it's always set as the first one
  val currentCaptureDeviceIndex = Signal(0)

  val currentCaptureDevice = captureDevices.zip(currentCaptureDeviceIndex).map {
    case (devices, devIndex) if devices.nonEmpty => Some(devices(devIndex % devices.size))
    case _ => None
  }

  (for {
    fm     <- flowManager
    conv   <- conversation
    device <- currentCaptureDevice
  } yield (fm, conv, device)) {
    case (fm, conv, Some(currentDevice)) => fm.setVideoCaptureDevice(conv.remoteId, currentDevice.id)
    case _ =>
  }

  val cameraFailed = flowManager.flatMap(_.cameraFailedSig)

  val userStorage = callingZms.map(_.usersStorage)
  val prefs       = callingZms.map(_.prefs)

  val callingService = callingZms.map(_.calling).disableAutowiring()

  val callingServiceAndCurrentConvId =
    for {
      cs <- callingService
      c  <- callConvId
    } yield (cs, c)


  val conversation = callingZms.zip(callConvId) flatMap { case (z, cId) => z.convsStorage.signal(cId) }
  val conversationName = conversation.map(_.displayName)
  val conversationMembers = conversation.flatMap(conv => ConversationMembersSignal(conv.id))

  val otherUser = Signal(isGroupCall, userStorage, callConvId).flatMap {
    case (false, usersStorage, convId) =>
      usersStorage.optSignal(UserId(convId.str)) // one-to-one conversation has the same id as the other user, so we can access it directly
    case _ => Signal.const[Option[UserData]](None) //Need a none signal to help with further signals
  }

  def leaveCall(): Unit = {
    verbose(s"leaveCall")
    for {
      cId <- callConvId.head
      cs  <- callingService.head
    } yield cs.endCall(cId)
  }

  def toggleMuted(): Unit = {
    verbose(s"toggleMuted")
    for {
      muted <- isMuted.head
      cs    <- callingService.head
    } yield cs.setCallMuted(!muted)
  }

  def toggleVideo(): Unit = {
    verbose(s"toggleVideo")
    for {
      st  <- videoSendState.head
      cId <- callConvId.head
      cs  <- callingService.head
    } yield {
      import VideoState._
      cs.setVideoSendState(cId, if (st != Started) Started else Stopped)
    }
  }

  def setVideoPause(pause: Boolean): Unit = {
    verbose(s"setVideoPause: $pause")
    for {
      st  <- videoSendState.head
      cId <- callConvId.head
      cs  <- callingService.head
    } yield {
      import VideoState._
      val targetSt = st match {
        case Started if pause => Paused
        case Paused if !pause => Started
        case _ => st
      }
      cs.setVideoSendState(cId, targetSt)
    }
  }

  private var _wasUiActiveOnCallStart = false

  def wasUiActiveOnCallStart = _wasUiActiveOnCallStart

  val onCallStarted = isCallActive.onChanged.filter(_ == true).map { _ =>
    val active = ZMessaging.currentGlobal.lifecycle.uiActive.currentValue.getOrElse(false)
    _wasUiActiveOnCallStart = active
    active
  }

  onCallStarted.on(Threading.Ui) { _ =>
    CallingActivity.start(cxt)
  }(EventContext.Global)

  isCallEstablished.onChanged.filter(_ == true) { _ =>
    soundController.playCallEstablishedSound()
  }

  isCallActive.onChanged.filter(_ == false) { _ =>
    soundController.playCallEndedSound()
  }

  isCallActive.onChanged.filter(_ == false).on(Threading.Ui) { _ =>
    screenManager.releaseWakeLock()
  }(EventContext.Global)


  (for {
    v  <- isVideoCall
    st <- callStateOpt
    callingShown <- callControlsVisible
  } yield (v, callingShown, st)) {
    case (true, _, _)                       => screenManager.setStayAwake()
    case (false, true, Some(OtherCalling))  => screenManager.setStayAwake()
    case (false, true, Some(SelfCalling |
                            SelfJoining |
                            SelfConnected)) => screenManager.setProximitySensorEnabled()
    case _                                  => screenManager.releaseWakeLock()
  }

  (for {
    m <- isMuted
    i <- isCallIncoming
  } yield (m, i)) { case (m, i) =>
    soundController.setIncomingRingTonePlaying(!m && i)
  }

  val convDegraded = conversation.map(_.verified == Verification.UNVERIFIED)
    .orElse(Signal(false))
    .disableAutowiring()

  val degradationWarningText = convDegraded.flatMap {
    case false => Signal(Option.empty[String])
    case true =>
      (for {
        zms <- callingZms
        convId <- callConvId
      } yield {
        zms.membersStorage.activeMembers(convId).flatMap { ids =>
          zms.usersStorage.listSignal(ids)
        }.map(_.filter(_.verified != Verification.VERIFIED).toList)
      }).flatten.map {
        case u1 :: u2 :: Nil =>
          Some(getString(R.string.conversation__degraded_confirmation__header__multiple_user, u1.name, u2.name))
        case l if l.size > 2 =>
          Some(getString(R.string.conversation__degraded_confirmation__header__someone))
        case List(u) =>
          //TODO handle string for case where user adds multiple clients
          Some(getQuantityString(R.plurals.conversation__degraded_confirmation__header__single_user, 1, u.name))
        case _ => None
      }
  }

  (for {
    v <- isVideoCall
    o <- isCallOutgoing
    d <- convDegraded
  } yield (v, o & !d)) { case (v, play) =>
    soundController.setOutgoingRingTonePlaying(play, v)
  }

  //Use Audio view to show conversation degraded screen for calling
  val showVideoView = convDegraded.flatMap {
    case true  => Signal(false)
    case false => isVideoCall
  }.disableAutowiring()

  val selfUser = callingZms flatMap (_.users.selfUser)

  val callerId = currentCallOpt flatMap {
    case Some(info) =>
      (info.others, info.state) match {
        case (_, Some(SelfCalling)) => selfUser.map(_.id)
        case (others, Some(OtherCalling)) if others.size == 1 => Signal.const(others.head)
        case _ => Signal.empty[UserId] //TODO Dean do I need this information for other call states?
      }
    case _ => Signal.empty[UserId]
  }

  val callerData = userStorage.zip(callerId).flatMap { case (storage, id) => storage.signal(id) }

  /////////////////////////////////////////////////////////////////////////////////
  /// TODO A lot of the following code should probably be moved to some other UI controller for the views that use them
  /////////////////////////////////////////////////////////////////////////////////

  val flowId = for {
    zms    <- callingZms
    convId <- callConvId
    conv   <- zms.convsStorage.signal(convId)
    rConvId = conv.remoteId
    userData <- otherUser
  } yield (rConvId, userData.map(_.id))

  def setVideoPreview(view: Option[VideoPreview]): Unit = {
    flowManager.on(Threading.Ui) { fm =>
      verbose(s"Setting VideoPreview on Flowmanager, view: $view")
      fm.setVideoPreview(view.orNull)
    }
  }

  def setVideoView(view: Option[VideoRenderer]): Unit = {
    (for {
      fm <- flowManager
      (rConvId, userId) <- flowId
    } yield (fm, rConvId, userId)).on(Threading.Ui) {
      case (fm, rConvId, userId) =>
        verbose(s"Setting ViewRenderer on Flowmanager, rConvId: $rConvId, userId: $userId, view: $view")
        fm.setVideoView(rConvId, userId, view.orNull)
    }
  }

  val callBannerText = Signal(isVideoCall, callState).map {
    case (_,     SelfCalling)   => R.string.call_banner_outgoing
    case (true,  OtherCalling)  => R.string.call_banner_incoming_video
    case (false, OtherCalling)  => R.string.call_banner_incoming
    case (_,     SelfJoining)   => R.string.call_banner_joining
    case (_,     SelfConnected) => R.string.call_banner_tap_to_return_to_call
    case _                      => R.string.empty_string
  }

  val subtitleText: Signal[String] = convDegraded.flatMap {
    case true => Signal("")
    case false => (for {
      video <- isVideoCall
      state <- callState
      dur   <- duration
    } yield (video, state, dur)).map {
      case (true,  SelfCalling,  _)  => cxt.getString(R.string.calling__header__outgoing_video_subtitle)
      case (false, SelfCalling,  _)  => cxt.getString(R.string.calling__header__outgoing_subtitle)
      case (true,  OtherCalling, _)  => cxt.getString(R.string.calling__header__incoming_subtitle__video)
      case (false, OtherCalling, _)  => cxt.getString(R.string.calling__header__incoming_subtitle)
      case (_,     SelfJoining,  _)  => cxt.getString(R.string.calling__header__joining)
      case (_,     SelfConnected, d) => d
      case _ => ""
    }
  }

  def stateMessageText(userId: UserId): Signal[Option[String]] = Signal(callState, cameraFailed, allVideoReceiveStates.map(_.getOrElse(userId, Unknown)), conversationName).map { vs =>
    verbose(s"Message Text: $vs")
    val r = vs match {
      case (SelfCalling,   true, _,             _)             => Option(cxt.getString(R.string.calling__self_preview_unavailable_long))
      case (SelfJoining,   _,    _,             _)             => Option(cxt.getString(R.string.ongoing__connecting))
      case (SelfConnected, _,    BadConnection, _)             => Option(cxt.getString(R.string.ongoing__poor_connection_message))
      case (SelfConnected, _,    Paused,        _)             => Option(cxt.getString(R.string.video_paused))
      case (SelfConnected, _,    Stopped,       otherUserName) => Option(cxt.getString(R.string.ongoing__other_turned_off_video, otherUserName))
      case (SelfConnected, _,    Unknown,       otherUserName) => Option(cxt.getString(R.string.ongoing__other_unable_to_send_video, otherUserName))
      case _ => None
    }
    r
  }

  def continueDegradedCall(): Unit = callingServiceAndCurrentConvId.head.map {
    case (cs, _) => cs.continueDegradedCall()
  }

  def vibrate(): Unit = {
    import com.waz.zclient.utils.ContextUtils._
    val audioManager = Option(inject[AudioManager])
    val vibrator = Option(inject[Vibrator])

    val disableRepeat = -1
    (audioManager, vibrator) match {
      case (Some(am), Some(vib)) if am.getRingerMode != AudioManager.RINGER_MODE_SILENT =>
        DeprecationUtils.vibrate(vib, getIntArray(R.array.call_control_enter).map(_.toLong), disableRepeat)
      case _ =>
    }
  }

  lazy val speakerButton = ButtonSignal(callingZms.map(_.mediamanager), callingZms.flatMap(_.mediamanager.isSpeakerOn)) {
    case (mm, isSpeakerSet) => mm.setSpeaker(!isSpeakerSet)
  }.disableAutowiring()

  val isTablet = Signal(!LayoutSpec.isPhone(cxt))
}

private class ScreenManager(implicit injector: Injector) extends Injectable {

  private val TAG = "CALLING_WAKE_LOCK"

  private val powerManager = Option(inject[PowerManager])

  private var stayAwake = false
  private var wakeLock: Option[PowerManager#WakeLock] = None

  def setStayAwake() = {
    (stayAwake, wakeLock) match {
      case (_, None) | (false, Some(_)) =>
        this.stayAwake = true
        createWakeLock();
      case _ => //already set
    }
  }

  def setProximitySensorEnabled() = {
    (stayAwake, wakeLock) match {
      case (_, None) | (true, Some(_)) =>
        this.stayAwake = false
        createWakeLock();
      case _ => //already set
    }
  }

  private def createWakeLock() = {
    val flags = if (stayAwake)
      DeprecationUtils.WAKE_LOCK_OPTIONS
    else PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK
    releaseWakeLock()
    wakeLock = powerManager.map(_.newWakeLock(flags, TAG))
    verbose(s"Creating wakelock")
    wakeLock.foreach(_.acquire())
    verbose(s"Acquiring wakelock")
  }

  def releaseWakeLock() = {
    for (wl <- wakeLock if wl.isHeld) {
      wl.release()
      verbose(s"Releasing wakelock")
    }
    wakeLock = None
  }
}

object CallController {
  val VideoCallMaxMembers:Int = 4
  case class CallParticipantInfo(userId: UserId, assetId: Option[AssetId], displayName: String, isGuest: Boolean, isVerified: Boolean, isVideoEnabled: Boolean)
}
