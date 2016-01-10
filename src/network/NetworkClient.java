/***********************************************************************************
* Copyright (c) 2015 /// Project SWG /// www.projectswg.com                        *
*                                                                                  *
* ProjectSWG is the first NGE emulator for Star Wars Galaxies founded on           *
* July 7th, 2011 after SOE announced the official shutdown of Star Wars Galaxies.  *
* Our goal is to create an emulator which will provide a server for players to     *
* continue playing a game similar to the one they used to play. We are basing      *
* it on the final publish of the game prior to end-game events.                    *
*                                                                                  *
* This file is part of Holocore.                                                   *
*                                                                                  *
* -------------------------------------------------------------------------------- *
*                                                                                  *
* Holocore is free software: you can redistribute it and/or modify                 *
* it under the terms of the GNU Affero General Public License as                   *
* published by the Free Software Foundation, either version 3 of the               *
* License, or (at your option) any later version.                                  *
*                                                                                  *
* Holocore is distributed in the hope that it will be useful,                      *
* but WITHOUT ANY WARRANTY; without even the implied warranty of                   *
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the                    *
* GNU Affero General Public License for more details.                              *
*                                                                                  *
* You should have received a copy of the GNU Affero General Public License         *
* along with Holocore.  If not, see <http://www.gnu.org/licenses/>.                *
*                                                                                  *
***********************************************************************************/
package network;

import intents.network.ConnectionOpenedIntent;
import intents.network.InboundPacketIntent;

import java.io.EOFException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedList;
import java.util.List;

import resources.control.Intent;
import network.encryption.Compression;
import network.packets.Packet;
import network.packets.swg.SWGPacket;
import network.packets.swg.zone.object_controller.ObjectController;

public class NetworkClient {
	
	private static final int DEFAULT_BUFFER = 128;
	
	private final Object prevPacketIntentMutex = new Object();
	private final Object outboundMutex = new Object();
	private final Object bufferMutex = new Object();
	private final InetSocketAddress address;
	private final long networkId;
	private final PacketSender packetSender;
	private Intent prevPacketIntent;
	private ByteBuffer buffer;
	private long lastBufferSizeModification;
	
	public NetworkClient(InetSocketAddress address, long networkId, PacketSender packetSender) {
		this.address = address;
		this.networkId = networkId;
		this.packetSender = packetSender;
		this.buffer = ByteBuffer.allocate(DEFAULT_BUFFER);
		lastBufferSizeModification = System.nanoTime();
		prevPacketIntent = null;
	}
	
	public InetSocketAddress getAddress() {
		return address;
	}
	
	public long getNetworkId() {
		return networkId;
	}
	
	public void onConnected() {
		synchronized (prevPacketIntentMutex) {
			prevPacketIntent = new ConnectionOpenedIntent(networkId);
			prevPacketIntent.broadcast();
		}
	}
	
	public void sendPacket(Packet p) {
		byte [] encoded = p.encode().array();
		int decompressedLength = encoded.length;
		boolean compressed = encoded.length >= 16;
		if (compressed) {
			byte [] compressedData = Compression.compress(encoded);
			if (compressedData.length >= encoded.length)
				compressed = false;
			else
				encoded = compressedData;
		}
		ByteBuffer data = ByteBuffer.allocate(encoded.length + 5).order(ByteOrder.LITTLE_ENDIAN);
		byte bitmask = 0;
		bitmask |= (compressed?1:0) << 0; // Compressed
		bitmask |= 1 << 1; // SWG
		data.put(bitmask);
		data.putShort((short) encoded.length);
		data.putShort((short) decompressedLength);
		data.put(encoded);
		synchronized (outboundMutex) {
			packetSender.sendPacket(address, data.array());
		}
	}
	
	public void addToBuffer(byte [] data) {
		synchronized (bufferMutex) {
			if (data.length > buffer.remaining()) { // Increase size
				int nCapacity = buffer.capacity() * 2;
				while (nCapacity < buffer.position()+data.length)
					nCapacity *= 2;
				ByteBuffer bb = ByteBuffer.allocate(nCapacity);
				buffer.flip();
				bb.put(buffer);
				bb.put(data);
				this.buffer = bb;
				lastBufferSizeModification = System.nanoTime();
			} else {
				buffer.put(data);
				if (buffer.position() < buffer.capacity()/4 && (System.nanoTime()-lastBufferSizeModification) >= 1E9)
					shrinkBuffer();
			}
		}
	}
	
	public boolean process() {
		List <Packet> packets;
		synchronized (bufferMutex) {
			buffer.flip();
			packets = processPackets();
			buffer.compact();
		}
		synchronized (prevPacketIntentMutex) {
			for (Packet p : packets) {
				p.setAddress(address.getAddress());
				p.setPort(address.getPort());
				InboundPacketIntent i = new InboundPacketIntent(p, networkId);
				i.broadcastAfterIntent(prevPacketIntent);
				prevPacketIntent = i;
			}
		}
		return packets.size() > 0;
	}
	
	private void shrinkBuffer() {
		synchronized (bufferMutex) {
			int nCapacity = DEFAULT_BUFFER;
			while (nCapacity < buffer.position())
				nCapacity *= 2;
			if (nCapacity >= buffer.capacity())
				return;
			ByteBuffer bb = ByteBuffer.allocate(nCapacity).order(ByteOrder.LITTLE_ENDIAN);
			buffer.flip();
			bb.put(buffer);
			buffer = bb;
			lastBufferSizeModification = System.nanoTime();
		}
	}
	
	private List<Packet> processPackets() {
		List <Packet> packets = new LinkedList<>();
		Packet p = null;
		try {
			while (buffer.hasRemaining()) {
				p = processPacket();
				if (p != null)
					packets.add(p);
			}
		} catch (EOFException e) {
			System.err.println(e.getMessage());
		}
		return packets;
	}
	
	private Packet processPacket() throws EOFException {
		if (buffer.remaining() < 5)
			throw new EOFException("Not enough remaining data for header! Remaining: " + buffer.remaining());
		buffer.order(ByteOrder.LITTLE_ENDIAN);
		byte bitfield = buffer.get();
		boolean compressed = (bitfield & (1<<0)) != 0;
		boolean swg = (bitfield & (1<<1)) != 0;
		int length = buffer.getShort();
		int decompressedLength = buffer.getShort();
		if (buffer.remaining() < length) {
			buffer.position(buffer.position() - 5);
			throw new EOFException("Not enough remaining data! Remaining: " + buffer.remaining() + "  Length: " + length);
		}
		byte [] pData = new byte[length];
		buffer.get(pData);
		if (compressed) {
			pData = Compression.decompress(pData, decompressedLength);
		}
		if (swg)
			return processSWG(pData);
		else
			return processProtocol(pData);
	}
	
	private Packet processProtocol(byte [] data) {
		return null;
	}
	
	private SWGPacket processSWG(byte [] data) {
		if (data.length < 6) {
			System.err.println("Length too small: " + data.length);
			return null;
		}
		ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		int crc = buffer.getInt(2);
		if (crc == 0x80CE5E46)
			return ObjectController.decodeController(buffer);
		else {
			SWGPacket packet = PacketType.getForCrc(crc);
			if (packet != null)
				packet.decode(buffer);
			return packet;
		}
	}
	
	public String toString() {
		return "NetworkClient["+address+"]";
	}
	
}
