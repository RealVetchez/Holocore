package com.projectswg.holocore.services.gameplay.missions

import com.projectswg.common.data.CRC
import com.projectswg.common.data.encodables.oob.ProsePackage
import com.projectswg.common.data.encodables.oob.StringId
import com.projectswg.common.data.encodables.oob.waypoint.WaypointColor
import com.projectswg.common.data.encodables.oob.waypoint.WaypointPackage
import com.projectswg.common.data.location.Location
import com.projectswg.common.network.packets.swg.zone.object_controller.MissionAcceptRequest
import com.projectswg.common.network.packets.swg.zone.object_controller.MissionAcceptResponse
import com.projectswg.common.network.packets.swg.zone.object_controller.MissionListRequest
import com.projectswg.holocore.intents.gameplay.combat.CreatureKilledIntent
import com.projectswg.holocore.intents.support.global.chat.SystemMessageIntent
import com.projectswg.holocore.intents.support.global.network.InboundPacketIntent
import com.projectswg.holocore.intents.support.objects.swg.DestroyObjectIntent
import com.projectswg.holocore.intents.support.objects.swg.ObjectCreatedIntent
import com.projectswg.holocore.resources.support.data.server_info.StandardLog
import com.projectswg.holocore.resources.support.data.server_info.loader.npc.NpcStaticSpawnLoader
import com.projectswg.holocore.resources.support.global.player.Player
import com.projectswg.holocore.resources.support.npc.spawn.NPCCreator
import com.projectswg.holocore.resources.support.npc.spawn.SimpleSpawnInfo
import com.projectswg.holocore.resources.support.npc.spawn.Spawner
import com.projectswg.holocore.resources.support.npc.spawn.SpawnerType
import com.projectswg.holocore.resources.support.objects.ObjectCreator
import com.projectswg.holocore.resources.support.objects.permissions.AdminPermissions
import com.projectswg.holocore.resources.support.objects.swg.SWGObject
import com.projectswg.holocore.resources.support.objects.swg.creature.CreatureDifficulty
import com.projectswg.holocore.resources.support.objects.swg.creature.CreatureObject
import com.projectswg.holocore.resources.support.objects.swg.custom.AIObject
import com.projectswg.holocore.resources.support.objects.swg.mission.MissionObject
import com.projectswg.holocore.resources.support.objects.swg.waypoint.WaypointObject
import com.projectswg.holocore.services.support.objects.ObjectStorageService.ObjectLookup
import me.joshlarson.jlcommon.control.IntentHandler
import me.joshlarson.jlcommon.control.Service

class DestroyMissionService : Service() {

	private val maxAcceptedMissions = 2
	private val desiredAmountOfMissionObjects = 5
	private val missionsToGenerate = 1
	private val npcToMission = mutableMapOf<AIObject, MissionObject>()

	@IntentHandler
	private fun handleObjectCreated(objectCreatedIntent: ObjectCreatedIntent) {
		val swgObject = objectCreatedIntent.`object`

		if ("object/tangible/mission_bag/shared_mission_bag.iff" == swgObject.template) {
			synchronizeMissionObjects(swgObject)
		}
	}

	@IntentHandler
	private fun handleInboundPacket(inboundPacketIntent: InboundPacketIntent) {
		val packet = inboundPacketIntent.packet
		val player = inboundPacketIntent.player

		if (packet is MissionListRequest) {
			handleMissionListRequest(packet, player)
		} else if (packet is MissionAcceptRequest) {
			handleMissionAcceptRequest(packet, player)
		}
	}

	@IntentHandler
	private fun handleCreatureKilled(creatureKilledIntent: CreatureKilledIntent) {
		val corpse = creatureKilledIntent.corpse
		val missionObject = npcToMission.remove(corpse)
		if (missionObject != null) {
			val owner = missionObject.owner
			if (owner != null) {
				handleMissionCompleted(owner, missionObject)
			}
		}
	}

	private fun handleMissionCompleted(owner: Player, missionObject: MissionObject) {
		StandardLog.onPlayerEvent(this, owner, "completed %s", missionObject)
		DestroyObjectIntent.broadcast(missionObject)
		val reward = missionObject.reward
		owner.creatureObject.addToBank(reward.toLong())
		val missionComplete = StringId("mission/mission_generic", "success_w_amount")
		SystemMessageIntent.broadcastPersonal(owner, ProsePackage(missionComplete, "DI", reward))
	}

	private fun handleMissionAcceptRequest(missionAcceptRequest: MissionAcceptRequest, player: Player) {
		if (!isDestroyMissionTerminal(missionAcceptRequest.terminalId)) {
			return
		}

		val missionId = missionAcceptRequest.missionId
		val missionObject = ObjectLookup.getObjectById(missionId) as MissionObject?

		if (missionObject != null) {
			val creatureObject = player.creatureObject
			val missionBag = creatureObject.missionBag
			val datapad = creatureObject.datapad

			if (datapad.containedObjects.size >= maxAcceptedMissions) {
				handleTooManyMissions(creatureObject, missionId, missionAcceptRequest, player)
				return
			}

			if (!missionBag.containedObjects.contains(missionObject)) {
				StandardLog.onPlayerError(this, player, "requested to accept mission not in their mission_bag")
				return
			}


			val missionAcceptResponse = MissionAcceptResponse(creatureObject.objectId)
			missionAcceptResponse.missionObjectId = missionId
			missionAcceptResponse.terminalType = missionAcceptRequest.terminalType.toInt()
			missionAcceptResponse.success = 1
			creatureObject.sendSelf(missionAcceptResponse)

			missionObject.moveToContainer(datapad)
			val location = creatureObject.worldLocation
			missionObject.waypointPackage = createWaypoint(location)

			spawnNpc(location, missionObject)
			StandardLog.onPlayerEvent(this, player, "accepted %s", missionObject)
		}
	}

	private fun spawnNpc(location: Location, missionObject: MissionObject) {
		val egg = ObjectCreator.createObjectFromTemplate(SpawnerType.MISSION_EASY.objectTemplate)
		egg.containerPermissions = AdminPermissions.getPermissions()
		egg.moveToContainer(null, location)
		ObjectCreatedIntent.broadcast(egg)

		val spawnInfo = SimpleSpawnInfo.builder()
			.withNpcId("humanoid_kobola_guard")
			.withDifficulty(CreatureDifficulty.NORMAL)
			.withSpawnerFlag(NpcStaticSpawnLoader.SpawnerFlag.ATTACKABLE)
			.withMinLevel(8)
			.withMaxLevel(12)
			.withLocation(location)
			.build()

		val npc = NPCCreator.createNPC(Spawner(spawnInfo, egg))
		npcToMission[npc] = missionObject
	}

	private fun handleTooManyMissions(creatureObject: CreatureObject, missionId: Long, missionAcceptRequest: MissionAcceptRequest, player: Player) {
		val missionAcceptResponse = MissionAcceptResponse(creatureObject.objectId)
		missionAcceptResponse.missionObjectId = missionId
		missionAcceptResponse.terminalType = missionAcceptRequest.terminalType.toInt()
		missionAcceptResponse.success = 0
		creatureObject.sendSelf(missionAcceptResponse)
		SystemMessageIntent.broadcastPersonal(player, ProsePackage("mission/mission_generic", "too_many_missions"))
	}

	private fun createWaypoint(location: Location): WaypointPackage {
		val waypoint = ObjectCreator.createObjectFromTemplate("object/waypoint/shared_waypoint.iff") as WaypointObject
		waypoint.setPosition(location.terrain, location.x, location.y, location.z)
		waypoint.color = WaypointColor.YELLOW
		ObjectCreatedIntent(waypoint).broadcast()
		return waypoint.oob
	}

	private fun handleMissionListRequest(missionListRequest: MissionListRequest, player: Player) {
		if (!isDestroyMissionTerminal(missionListRequest.terminalId)) {
			return
		}

		val tickCount = missionListRequest.tickCount
		val creatureObject = player.creatureObject
		val missionBag = creatureObject.missionBag

		synchronizeMissionObjects(missionBag)
		generateMissions(missionBag, creatureObject, tickCount)
	}

	private fun isDestroyMissionTerminal(terminalId: Long): Boolean {
		val objectById = ObjectLookup.getObjectById(terminalId)

		return objectById?.template == "object/tangible/terminal/shared_terminal_mission.iff"
	}

	private fun generateMissions(missionBag: SWGObject, creatureObject: CreatureObject, tickCount: Byte) {
		val containedObjects = missionBag.containedObjects
		val amountOfAvailableMissionObjects = containedObjects.size

		if (missionsToGenerate > amountOfAvailableMissionObjects) {
			throw IllegalStateException("Amount of missions to generate ($missionsToGenerate) was larger than the amount of available MissionObjects ($amountOfAvailableMissionObjects)")
		}

		val iterator = containedObjects.iterator()
		for (i in 1..missionsToGenerate) {
			val containedObject = iterator.next() as MissionObject
			updateMissionObject(containedObject, creatureObject.location)
			containedObject.tickCount = tickCount.toInt()
		}
	}

	private fun synchronizeMissionObjects(missionBag: SWGObject) {
		destroyExcessMissions(missionBag)
		createMissingMissions(missionBag)
	}

	private fun createMissingMissions(missionBag: SWGObject) {
		val actualAmountOfMissionObjects = missionBag.containedObjects.size
		val missionsToCreate = desiredAmountOfMissionObjects - actualAmountOfMissionObjects

		for (i in 1..missionsToCreate) {
			val missionObject = createMissionObject()
			missionObject.moveToContainer(missionBag)
		}
	}

	private fun destroyExcessMissions(missionBag: SWGObject) {
		val actualAmountOfMissions = missionBag.containedObjects.size
		val iterator = missionBag.containedObjects.iterator()
		for (i in desiredAmountOfMissionObjects until actualAmountOfMissions) {
			DestroyObjectIntent.broadcast(iterator.next())
		}
	}

	private fun createMissionObject(): MissionObject {
		val missionObject = ObjectCreator.createObjectFromTemplate("object/mission/shared_mission_object.iff") as MissionObject
		ObjectCreatedIntent.broadcast(missionObject)

		return missionObject
	}

	private fun updateMissionObject(missionObject: MissionObject, location: Location) {
		missionObject.missionType = CRC("destroy")
		missionObject.missionCreator = "Holocore"
		missionObject.difficulty = 10
		missionObject.targetName = "NPCs"
		missionObject.title = StringId("mission/mission_destroy_neutral_easy_npc", "m1t")
		missionObject.description = StringId("mission/mission_destroy_neutral_easy_npc", "m1d")
		missionObject.reward = 100
		missionObject.targetAppearance = CRC("object/mobile/shared_dressed_kobola_guard_trandoshan_female_01.iff")
		val missionLocation = MissionObject.MissionLocation()
		missionLocation.location = location.position
		missionLocation.terrain = location.terrain
		missionObject.startLocation = missionLocation
		missionObject.missionLocation = missionLocation
	}

}