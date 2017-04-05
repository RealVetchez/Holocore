/*******************************************************************************
 * Copyright (c) 2015 /// Project SWG /// www.projectswg.com
 *
 * ProjectSWG is the first NGE emulator for Star Wars Galaxies founded on
 * July 7th, 2011 after SOE announced the official shutdown of Star Wars Galaxies.
 * Our goal is to create an emulator which will provide a server for players to
 * continue playing a game similar to the one they used to play. We are basing
 * it on the final publish of the game prior to end-game events.
 *
 * This file is part of Holocore.
 *
 * --------------------------------------------------------------------------------
 *
 * Holocore is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Holocore is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Holocore.  If not, see <http://www.gnu.org/licenses/>
 ******************************************************************************/
package resources.objects.group;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import network.packets.Packet;
import network.packets.swg.zone.baselines.Baseline;
import resources.Location;
import resources.Terrain;
import resources.collections.SWGList;
import resources.encodables.Encodable;
import resources.network.BaselineBuilder;
import resources.objects.SWGObject;
import resources.objects.creature.CreatureObject;
import resources.player.Player;
import utilities.Encoder;

import com.projectswg.common.concurrency.SynchronizedMap;
import com.projectswg.common.debug.Assert;

public class GroupObject extends SWGObject {
	
	private final SWGList<GroupMember>	groupMembers		= new SWGList<>(6, 2, Encoder.StringType.ASCII);
	private final Map<Long, GroupMember>memberMap			= new SynchronizedMap<>();
	private final PickupPointTimer		pickupPointTimer	= new PickupPointTimer();
	
	private CreatureObject	leader		= null;
	private LootRule		lootRule	= LootRule.RANDOM;
	private short			level		= 0;
	private long			lootMaster	= 0;
	
	public GroupObject(long objectId) {
		super(objectId, Baseline.BaselineType.GRUP);
		setLocation(new Location(0, 0, 0, Terrain.GONE));
	}
	
	@Override
	public void createBaseline6(Player target, BaselineBuilder bb) {
		super.createBaseline6(target, bb); // BASE06 -- 2 variables
		bb.addObject(groupMembers); // 2 -- NOTE: First person is the leader
		bb.addInt(0); // formationmembers // 3
		bb.addInt(0); // updateCount
		bb.addAscii(""); // groupName // 4
		bb.addShort(level); // 5
		bb.addInt(0); // formationNameCrc // 6
		bb.addLong(lootMaster); // 7
		bb.addInt(lootRule.getId()); // 8
		bb.addObject(pickupPointTimer); // 9
		bb.addAscii(""); // PickupPoint planetName // 10
		bb.addFloat(0); // x
		bb.addFloat(0); // y
		bb.addFloat(0); // z
		
		bb.incrementOperandCount(9);
	}
	
	public void formGroup(CreatureObject leader, CreatureObject member) {
		if (this.leader != null)
			throw new IllegalStateException("Group already formed!");
		this.leader = leader;
		addGroupMembers(leader, member);
	}
	
	public void addMember(CreatureObject creature) {
		addGroupMembers(creature);
		calculateLevel();
	}
	
	public void removeMember(CreatureObject creature) {
		if (leader.equals(creature) && size() >= 2) {
			setLeader(groupMembers.get(1));
		}
		removeGroupMembers(creature);
		calculateLevel();
	}
	
	public int size() {
		return groupMembers.size();
	}
	
	public boolean isFull() {
		return size() >= 8;
	}
	
	public void updateMember(CreatureObject object) {
		if (memberMap.containsKey(object.getObjectId()))
			addCustomAware(object);
		else
			removeCustomAware(object);
	}
	
	public long getLeaderId() {
		return leader.getObjectId();
	}
	
	public Player getLeaderPlayer() {
		return leader.getOwner();
	}
	
	public void setLeader(CreatureObject object) {
		Assert.notNull(object);
		GroupMember member = memberMap.get(object.getObjectId());
		Assert.notNull(member);
		setLeader(member);
	}
	
	public short getLevel() {
		return level;
	}
	
	public long getLootMaster() {
		return lootMaster;
	}
	
	public LootRule getLootRule() {
		return lootRule;
	}
	
	public void setLevel(short level) {
		this.level = level;
		sendDelta(6, 5, level);
	}
	
	public void setLootMaster(long lootMaster) {
		this.lootMaster = lootMaster;
	}
	
	public void setLootRule(int lootRule) {
		setLootRule(LootRule.fromId(lootRule));
	}
	
	public void setLootRule(LootRule lootRule) {
		this.lootRule = lootRule;
		sendLootRuleDelta(lootRule.getId());
	}
	
	public void sendLootRuleDelta(int lootRule) {
		sendDelta(6, 8, lootRule);
	}
	
	public Map<String, Long> getGroupMembers() {
		Map<String, Long> members = new HashMap<>();
		iterateGroup(member -> members.put(member.getName(), member.getId()));
		return members;
	}
	
	public Set<CreatureObject> getGroupMemberObjects() {
		Set<CreatureObject> memberObjects = new HashSet<>();
		iterateGroup(member -> memberObjects.add(member.getCreature()));
		return memberObjects;
	}
	
	public void disbandGroup() {
		CreatureObject [] creatures;
		synchronized (groupMembers) {
			creatures = new CreatureObject[size()];
			int i = 0;
			for (GroupMember member : groupMembers) {
				creatures[i++] = member.getCreature();
			}
		}
		removeGroupMembers(creatures);
	}
	
	private void setLeader(GroupMember member) {
		Assert.notNull(member);
		this.leader = member.getCreature();
		synchronized (groupMembers) {
			Assert.test(groupMembers.contains(member));
			int swapIndex = groupMembers.indexOf(member);
			GroupMember tmp = groupMembers.get(0);
			groupMembers.set(0, member);
			groupMembers.set(swapIndex, tmp);
			groupMembers.sendDeltaMessage(this);
		}
	}
	
	private void calculateLevel() {
		AtomicInteger newLevel = new AtomicInteger(0);
		iterateGroup(member -> newLevel.set(Math.max(newLevel.get(), member.getCreature().getLevel())));
		setLevel((short) newLevel.get());
	}
	
	private void iterateGroup(Consumer<GroupMember> consumer) {
		synchronized (groupMembers) {
			groupMembers.forEach(consumer);
		}
	}
	
	private void addGroupMembers(CreatureObject ... creatures) {
		for (CreatureObject creature : creatures) {
			Assert.test(!isCustomAware(creature));
			Assert.test(creature.getGroupId() == 0);
			GroupMember member = new GroupMember(creature);
			Assert.isNull(memberMap.put(creature.getObjectId(), member));
			addCustomAware(creature);
			groupMembers.add(member);
			creature.setGroupId(getObjectId());
		}
		groupMembers.sendDeltaMessage(this);
	}
	
	private void removeGroupMembers(CreatureObject ... creatures) {
		for (CreatureObject creature : creatures) {
			Assert.test(creature.getGroupId() == getObjectId());
			GroupMember member = memberMap.remove(creature.getObjectId());
			Assert.notNull(member);
			creature.setGroupId(0);
			groupMembers.remove(member);
			removeCustomAware(creature);
		}
		groupMembers.sendDeltaMessage(this);
	}
	
	private static class PickupPointTimer implements Encodable {
		
		private int start;
		private int end;
		
		public PickupPointTimer() {
			start = 0;
			end = 0;
		}
		
		@Override
		public byte[] encode() {
			return ByteBuffer.allocate(8).putInt(start).putInt(end).array();
		}
		
		@Override
		public void decode(ByteBuffer data) {
			start = Packet.getInt(data);
			end = Packet.getInt(data);
		}
		
	}
	
	private static class GroupMember implements Encodable {
		
		private CreatureObject creature;
		
		public GroupMember(CreatureObject creature) {
			this.creature = creature;
		}
		
		@Override
		public byte[] encode() {
			String name = creature.getObjectName();
			ByteBuffer bb = ByteBuffer.allocate(10 + name.length());
			Packet.addLong(bb, creature.getObjectId());
			Packet.addAscii(bb, name);
			return bb.array();
		}
		
		@Override
		public void decode(ByteBuffer data) {
			
		}

		public long getId() {
			return creature.getObjectId();
		}
		
		public CreatureObject getCreature() {
			return creature;
		}
		
		public String getName() {
			return creature.getObjectName();
		}
		
		@Override
		public boolean equals(Object o) {
			if (!(o instanceof GroupMember))
				return false;
			
			return creature.equals(((GroupMember) o).getCreature());
		}
		
		@Override
		public int hashCode() {
			return creature.hashCode();
		}
	}
}
