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
package com.waz.zclient.calling.views

import android.content.Context
import android.util.AttributeSet
import android.widget.GridLayout
import com.waz.ZLog.ImplicitTag.implicitLogTag
import com.waz.ZLog._
import com.waz.service.call.Avs.VideoState
import com.waz.utils.events.{EventStream, Signal}
import com.waz.utils.returning
import com.waz.zclient.calling.controllers.CallController
import com.waz.zclient.calling.views.CallControlButtonView.ButtonColor
import com.waz.zclient.common.controllers.UserAccountsController
import com.waz.zclient.paintcode._
import com.waz.zclient.utils.RichView
import com.waz.zclient.{R, ViewHelper}

class ControlsView(val context: Context, val attrs: AttributeSet, val defStyleAttr: Int) extends GridLayout(context, attrs, defStyleAttr) with ViewHelper {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null)

  inflate(R.layout.calling__controls__grid, this)
  setColumnCount(3)
  setRowCount(2)

  private lazy val controller = inject[CallController]
  private lazy val accountsController = inject[UserAccountsController]

  val onButtonClick = EventStream[Unit]

  private val isVideoBeingSent = controller.videoSendState.map(_ != VideoState.Stopped)

  private val incomingNotEstablished = Signal(controller.isCallIncoming, controller.isCallEstablished).map {
    case (in, est) =>
      verbose(s"incoming not established ($in, $est) => ${in && !est}")
      in && !est
  }
  // first row
  returning(findById[CallControlButtonView](R.id.mute_call)) { button =>
    button.set(WireStyleKit.drawMute, R.string.incoming__controls__ongoing__mute, mute)
    button.setEnabled(true)
    controller.isMuted.onUi(button.setActivated)
  }

  returning(findById[CallControlButtonView](R.id.video_call)) { button =>
    button.set(WireStyleKit.drawCamera, R.string.incoming__controls__ongoing__video, video)

    isVideoBeingSent.onUi(button.setActivated)

    Signal(
      controller.isCallIncoming,
      controller.isCallEstablished,
      controller.conversationMembers,
      controller.showVideoView,
      accountsController.isTeam
    ).map {
      case (in, est, members, show, team) =>
        (est || in) && (show || team || members.size == 2) && members.size <= CallController.VideoCallMaxMembers
    }.onUi(button.setEnabled)
  }

  returning(findById[CallControlButtonView](R.id.speaker_flip_call)) { button =>
    isVideoBeingSent.onUi {
      case true =>
        button.set(WireStyleKit.drawFlip, R.string.incoming__controls__ongoing__flip, flip)
        button.setEnabled(true)
      case false =>
        button.set(WireStyleKit.drawSpeaker, R.string.incoming__controls__ongoing__speaker, speaker)
        button.setEnabled(true)
    }
    Signal(controller.speakerButton.buttonState, isVideoBeingSent).onUi {
      case (buttonState, false) => button.setActivated(buttonState)
      case (_, true) => button.setActivated(false)
    }
  }

  // second row
  returning(findById[CallControlButtonView](R.id.reject_call)) { button =>
    button.setEnabled(true)
    button.set(WireStyleKit.drawHangUpCall, R.string.empty_string, leave, Some(ButtonColor.Red))
    incomingNotEstablished.onUi(button.setVisible)
  }

  returning(findById[CallControlButtonView](R.id.end_call)) { button =>
    button.set(WireStyleKit.drawHangUpCall, R.string.empty_string, leave, Some(ButtonColor.Red))
    button.setEnabled(true)
    incomingNotEstablished.map(!_).onUi(button.setVisible)
  }

  returning(findById[CallControlButtonView](R.id.accept_call)) { button =>
    button.set(WireStyleKit.drawAcceptCall, R.string.empty_string, accept, Some(ButtonColor.Green))
    button.setEnabled(true)
    incomingNotEstablished.onUi(button.setVisible)
  }

  private def accept(): Unit = {
    onButtonClick ! {}
    controller.continueDegradedCall()
  }

  private def leave(): Unit = {
    onButtonClick ! {}
    controller.leaveCall()
  }

  private def flip(): Unit = {
    onButtonClick ! {}
    controller.currentCaptureDeviceIndex.mutate(_ + 1)
  }

  private def speaker(): Unit = {
    onButtonClick ! {}
    controller.speakerButton.press()
  }

  private def video(): Unit = {
    onButtonClick ! {}
    controller.toggleVideo()
  }

  private def mute(): Unit = {
    onButtonClick ! {}
    controller.toggleMuted()
  }
}
