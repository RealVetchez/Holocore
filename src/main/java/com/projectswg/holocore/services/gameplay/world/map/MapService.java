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
package com.projectswg.holocore.services.gameplay.world.map;

import com.projectswg.common.data.encodables.map.MapLocation;
import com.projectswg.common.data.location.Location;
import com.projectswg.common.network.packets.SWGPacket;
import com.projectswg.common.network.packets.swg.zone.spatial.GetMapLocationsMessage;
import com.projectswg.common.network.packets.swg.zone.spatial.GetMapLocationsResponseMessage;
import com.projectswg.holocore.intents.support.global.network.InboundPacketIntent;
import com.projectswg.holocore.intents.support.objects.ObjectCreatedIntent;
import com.projectswg.holocore.resources.gameplay.world.map.MappingTemplate;
import com.projectswg.holocore.resources.support.data.server_info.loader.*;
import com.projectswg.holocore.resources.support.data.server_info.loader.PlanetMapCategoryLoader.PlanetMapCategoryInfo;
import com.projectswg.holocore.resources.support.global.player.Player;
import com.projectswg.holocore.resources.support.objects.swg.SWGObject;
import me.joshlarson.jlcommon.control.IntentHandler;
import me.joshlarson.jlcommon.control.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class MapService extends Service {
	
	private final Map<String, List<MapLocation>> staticMapLocations; // ex: NPC cities and buildings
	private final Map<String, List<MapLocation>> dynamicMapLocations; // ex: camps, faction presences (grids)
	private final Map<String, List<MapLocation>> persistentMapLocations; // ex: Player structures, vendors

	// Version is used to determine when the client needs to be updated. 0 sent by client if no map requested yet.
	// AtomicInteger used for synchronization
	private final AtomicInteger staticMapVersion = new AtomicInteger(1);
	private final AtomicInteger dynamicMapVersion = new AtomicInteger(1);
	private final AtomicInteger persistMapVersion = new AtomicInteger(1);
	
	public MapService() {
		staticMapLocations = new ConcurrentHashMap<>();
		dynamicMapLocations = new ConcurrentHashMap<>();
		persistentMapLocations = new ConcurrentHashMap<>();
	}

	@Override
	public boolean initialize() {
		loadStaticCityPoints();
		return true;
	}

	@IntentHandler
	private void handleInboundPacketIntent(InboundPacketIntent gpi) {
		SWGPacket packet = gpi.getPacket();
		if (packet instanceof GetMapLocationsMessage)
			handleMapLocationsRequest(gpi.getPlayer(), (GetMapLocationsMessage) packet);
	}
	
	@IntentHandler
	private void handleObjectCreatedIntent(ObjectCreatedIntent oci) {
		addMapLocation(oci.getObj(), MapType.STATIC);
	}
	
	private void handleMapLocationsRequest(Player player, GetMapLocationsMessage p) {
		String planet = p.getPlanet();

		int staticVer = staticMapVersion.get();
		int dynamicVer = dynamicMapVersion.get();
		int persistVer = persistMapVersion.get();

		// Only send list if the current map version isn't the same as the clients.
		List<MapLocation> staticLocs = (p.getVersionStatic() != staticVer ? staticMapLocations.get(planet) : null);
		List<MapLocation> dynamicLocs = (p.getVersionDynamic() != dynamicVer ? dynamicMapLocations.get(planet) : null);
		List<MapLocation> persistLocs = (p.getVersionPersist() != persistVer ? persistentMapLocations.get(planet) : null);

		GetMapLocationsResponseMessage responseMessage = new GetMapLocationsResponseMessage(planet,
				staticLocs, dynamicLocs, persistLocs, staticVer, dynamicVer, persistVer);

		player.sendPacket(responseMessage);
	}

	private void loadStaticCityPoints() {
		Collection<StaticCityPoint> allPoints = ServerData.INSTANCE.getStaticCityPoints().getAllPoints();
		PlanetMapCategoryInfo mapCategory = DataLoader.Companion.planetMapCategories().getCategoryByName("city");
		assert mapCategory != null;
		byte city = (byte) mapCategory.getIndex();
		for (StaticCityPoint point : allPoints) {
			List<MapLocation> locations = staticMapLocations.computeIfAbsent(point.getPlanet(), k -> new ArrayList<>());

			String name = point.getCity();
			float x = point.getX();
			float z = point.getZ();
			locations.add(new MapLocation(locations.size() + 1, name, x, z, city, (byte) 0, false));
		}
	}

	private void addMapLocation(SWGObject object, MapType type) {
		MappingTemplateLoader mappingTemplateLoader = DataLoader.Companion.mappingTemplates();
		MappingTemplate mappingTemplate = mappingTemplateLoader.getMappingTemplate(object.getTemplate());
		
		if (mappingTemplate == null) {
			return;
		}

		PlanetMapCategoryInfo category = DataLoader.Companion.planetMapCategories().getCategoryByName(mappingTemplate.getCategory());
		PlanetMapCategoryInfo subcategory = DataLoader.Companion.planetMapCategories().getCategoryByName(mappingTemplate.getSubcategory());

		MapLocation mapLocation = new MapLocation();
		mapLocation.setName(mappingTemplate.getName());
		mapLocation.setCategory((byte) (category == null ? 0 : category.getIndex()));
		mapLocation.setSubcategory((byte) (subcategory == null ? 0 : subcategory.getIndex()));
		Location objectLocation = object.getWorldLocation();
		mapLocation.setX((float) objectLocation.getX());
		mapLocation.setY((float) objectLocation.getZ());

		String planet = object.getTerrain().getName();

		switch (type) {
			case STATIC:
				addStaticMapLocation(planet, mapLocation);
				break;
			case DYNAMIC:
				addDynamicMapLocation(planet, mapLocation);
				break;
			case PERSISTENT:
				addPersistentMapLocation(planet, mapLocation);
				break;
		}
	}

	public void addStaticMapLocation(String planet, MapLocation location) {
		if (staticMapLocations.containsKey(planet)) {
			location.setId(staticMapLocations.get(planet).size() + 1);
		} else {
			location.setId(1);
			staticMapLocations.put(planet, new CopyOnWriteArrayList<>());
		}
		staticMapLocations.get(planet).add(location);
	}

	public void addDynamicMapLocation(String planet, MapLocation location) {
		if (dynamicMapLocations.containsKey(planet)) {
			location.setId(dynamicMapLocations.get(planet).size() + 1);
		} else {
			location.setId(1);
			dynamicMapLocations.put(planet, new CopyOnWriteArrayList<>());
		}
		dynamicMapLocations.get(planet).add(location);
	}

	public void addPersistentMapLocation(String planet, MapLocation location) {
		if (persistentMapLocations.containsKey(planet)) {
			location.setId(persistentMapLocations.get(planet).size() + 1);
		} else {
			location.setId(1);
			persistentMapLocations.put(planet, new CopyOnWriteArrayList<>());
		}
		persistentMapLocations.get(planet).add(location);
	}

	public enum MapType {
		STATIC,
		DYNAMIC,
		PERSISTENT
	}
	
}
