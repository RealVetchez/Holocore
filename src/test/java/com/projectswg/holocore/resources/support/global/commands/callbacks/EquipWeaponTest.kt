/***********************************************************************************
 * Copyright (c) 2024 /// Project SWG /// www.projectswg.com                       *
 *                                                                                 *
 * ProjectSWG is the first NGE emulator for Star Wars Galaxies founded on          *
 * July 7th, 2011 after SOE announced the official shutdown of Star Wars Galaxies. *
 * Our goal is to create an emulator which will provide a server for players to    *
 * continue playing a game similar to the one they used to play. We are basing     *
 * it on the final publish of the game prior to end-game events.                   *
 *                                                                                 *
 * This file is part of Holocore.                                                  *
 *                                                                                 *
 * --------------------------------------------------------------------------------*
 *                                                                                 *
 * Holocore is free software: you can redistribute it and/or modify                *
 * it under the terms of the GNU Affero General Public License as                  *
 * published by the Free Software Foundation, either version 3 of the              *
 * License, or (at your option) any later version.                                 *
 *                                                                                 *
 * Holocore is distributed in the hope that it will be useful,                     *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of                  *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the                   *
 * GNU Affero General Public License for more details.                             *
 *                                                                                 *
 * You should have received a copy of the GNU Affero General Public License        *
 * along with Holocore.  If not, see <http://www.gnu.org/licenses/>.               *
 ***********************************************************************************/
package com.projectswg.holocore.resources.support.global.commands.callbacks

import com.projectswg.common.data.CRC
import com.projectswg.common.network.packets.swg.zone.object_controller.CommandQueueEnqueue
import com.projectswg.holocore.intents.support.global.network.InboundPacketIntent
import com.projectswg.holocore.intents.support.objects.ObjectCreatedIntent
import com.projectswg.holocore.resources.support.global.player.Player
import com.projectswg.holocore.resources.support.objects.ObjectCreator
import com.projectswg.holocore.resources.support.objects.StaticItemCreator
import com.projectswg.holocore.resources.support.objects.swg.SWGObject
import com.projectswg.holocore.resources.support.objects.swg.weapon.DefaultWeaponFactory
import com.projectswg.holocore.resources.support.objects.swg.weapon.WeaponObject
import com.projectswg.holocore.services.support.global.commands.CommandExecutionService
import com.projectswg.holocore.services.support.global.commands.CommandQueueService
import com.projectswg.holocore.test.resources.GenericCreatureObject
import com.projectswg.holocore.test.runners.TestRunnerSimulatedWorld
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EquipWeaponTest : TestRunnerSimulatedWorld() {
	
	@BeforeEach
	fun setUp() {
		registerService(CommandQueueService(5))
		registerService(CommandExecutionService())
	}

	private fun transferItem(player: Player, item: SWGObject, container: SWGObject?) {
		val args = createArgsForEquippingAnItem(container ?: return)
		val transferItemArmorPacket = CommandQueueEnqueue(player.creatureObject.objectId, 0, CRC.getCrc("transferitemmisc"), item.objectId, args)

		InboundPacketIntent(player, transferItemArmorPacket).broadcast()
		waitForIntents()
	}

	@Test
	fun `can equip weapon when all requirements are fulfilled`() {
		val creatureObject = createCreatureObject()
		val player = creatureObject.owner ?: throw RuntimeException("Unable to access player")
		val oneHandSwordCL1 = createOneHandSwordCL1()
		oneHandSwordCL1.moveToContainer(creatureObject.inventory)

		transferItem(player, oneHandSwordCL1, creatureObject)
		
		assertEquals(creatureObject, oneHandSwordCL1.parent)
	}

	@Test
	fun `can not equip weapon if required combat level is too high`() {
		val creatureObject = createCreatureObject()
		val player = creatureObject.owner ?: throw RuntimeException("Unable to access player")
		val trandoshanHuntingRifle = createTrandoshanHuntingRifle()
		trandoshanHuntingRifle.moveToContainer(creatureObject.inventory)

		transferItem(player, trandoshanHuntingRifle, creatureObject)

		assertEquals(creatureObject.inventory, trandoshanHuntingRifle.parent)
	}

	private fun createArgsForEquippingAnItem(swgObject: SWGObject): String {
		return " ${swgObject.objectId} 4 0.000000 0.000000 0.000000"
	}

	private fun createCreatureObject(): GenericCreatureObject {
		val creatureObject = GenericCreatureObject(ObjectCreator.getNextObjectId())
		val defWeapon = DefaultWeaponFactory.createDefaultWeapon()
		defWeapon.moveToContainer(creatureObject)
		creatureObject.equippedWeapon = defWeapon
		broadcastAndWait(ObjectCreatedIntent(creatureObject))
		return creatureObject
	}

	private fun createOneHandSwordCL1(): WeaponObject {
		val oneHandSwordCL1 = StaticItemCreator.createItem("weapon_cl1_1h") as WeaponObject?
		oneHandSwordCL1 ?: throw RuntimeException("Unable to create weapon")
		broadcastAndWait(ObjectCreatedIntent(oneHandSwordCL1))
		return oneHandSwordCL1
	}

	private fun createTrandoshanHuntingRifle(): SWGObject {
		val trandoshanHuntingRifle = StaticItemCreator.createItem("weapon_rifle_trando_hunting") as WeaponObject?
		trandoshanHuntingRifle ?: throw RuntimeException("Unable to create weapon")
		broadcastAndWait(ObjectCreatedIntent(trandoshanHuntingRifle))
		return trandoshanHuntingRifle
	}
}