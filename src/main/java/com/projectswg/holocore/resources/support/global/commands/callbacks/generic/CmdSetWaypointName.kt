package com.projectswg.holocore.resources.support.global.commands.callbacks.generic

import com.projectswg.holocore.resources.support.global.commands.ICmdCallback
import com.projectswg.holocore.resources.support.global.player.Player
import com.projectswg.holocore.resources.support.objects.swg.SWGObject

class CmdSetWaypointName : ICmdCallback {
	override fun execute(player: Player, target: SWGObject?, args: String) {
		if (player.playerObject == null || target == null) {
			return
		}

		val waypoint = player.playerObject.getWaypoint(target.objectId) ?: return

		waypoint.name = args

		player.playerObject.updateWaypoint(waypoint)
	}
}
