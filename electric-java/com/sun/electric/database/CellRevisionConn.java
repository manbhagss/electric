/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: CellRevisionConn.java
 * Written by: Dmitry Nadezhin.
 *
 * Copyright (c) 2012, Static Free Software. All rights reserved.
 *
 * Electric(tm) is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * Electric(tm) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.sun.electric.database;

import com.sun.electric.database.id.CellId;
import com.sun.electric.database.id.ExportId;
import com.sun.electric.database.id.PortProtoId;
import com.sun.electric.util.collections.ArrayIterator;
import com.sun.electric.util.collections.ImmutableArrayList;
import com.sun.electric.util.collections.ImmutableList;
import com.sun.electric.util.memory.ObjSize;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of CellRevision in Java. Connectivity is represented by
 * SoftReferenced array-array structures.
 */
public class CellRevisionConn extends CellRevision {

    private static final int LOW_BITS = 6;
    private static final int LOW_SIZE = 1 << LOW_BITS;
    private static final int LOW_MASK = LOW_SIZE - 1;
    private static final Object[] emptyBlock = new Object[LOW_SIZE];
    private static final Object[][] emptyBlocks = {};

    public static class CellRevisionProvider extends CellRevisionProviderDefault {

        @Override
        public CellRevision createCellRevision(ImmutableCell c) {
            return new CellRevisionConn(c);
        }
    }

    private static class BlockBuffer {

        private boolean hasKilledObjs = false;
        private final ImmutableList<ImmutableElectricObject>[] newObjs = new ImmutableList[LOW_SIZE];

        private void putObj(int nodeId, ImmutableElectricObject obj) {
            int lowId = nodeId & LOW_MASK;
            newObjs[lowId] = ImmutableList.addFirst(newObjs[lowId], obj);
        }
    }
    private volatile SoftReference<Object[][]> nodeConnectionsRef = new SoftReference<Object[][]>(null);

    private CellRevisionConn(ImmutableCell d,
            ImmutableNodeInst.Iterable nodes,
            ImmutableArcInst.Iterable arcs, int[] arcIndex,
            ImmutableExport.Iterable exports, int[] exportIndex,
            BitSet techUsages, CellUsageInfo[] cellUsages,
            BitSet definedExports, int definedExportsLength, BitSet deletedExports) {
        super(d,
                nodes,
                arcs, arcIndex,
                exports, exportIndex,
                techUsages, cellUsages,
                definedExports, definedExportsLength, deletedExports);
    }

    private CellRevisionConn(ImmutableCell d) {
        this(d, CellRevision.getProvider().createNodeList(ImmutableNodeInst.NULL_ARRAY, null),
                CellRevision.getProvider().createArcList(ImmutableArcInst.NULL_ARRAY, null), CellRevision.NULL_INT_ARRAY,
                CellRevision.getProvider().createExportList(ImmutableExport.NULL_ARRAY, null), CellRevision.NULL_INT_ARRAY,
                CellRevision.makeTechUsages(d.techId), CellRevision.NULL_CELL_USAGE_INFO_ARRAY, CellRevision.EMPTY_BITSET, 0, CellRevision.EMPTY_BITSET);
        if (d.techId == null) {
            throw new NullPointerException("techId");
        }
    }

    @Override
    public boolean hasConnectionsOnNode(ImmutableNodeInst n) {
        Object nodeInfo = getNodeInfo(n);
        if (nodeInfo instanceof NodeConnections) {
            return ((NodeConnections) nodeInfo).hasConnectionsOnNode();
        } else if (nodeInfo instanceof ImmutableArcInst) {
            return true;
        } else if (nodeInfo instanceof PortConns) {
            return ((PortConns) nodeInfo).hasConnections();
        } else {
            return false;
        }
    }

    /**
     * Method to return the number of Connections on specified
     * ImmutableNodeInst.
     *
     * @param n specified ImmutableNodeInst
     * @return the number of Connections on specified ImmutableNodeInst.
     */
    @Override
    public int getNumConnectionsOnNode(ImmutableNodeInst n) {
        Object nodeInfo = getNodeInfo(n);
        if (nodeInfo instanceof NodeConnections) {
            return ((NodeConnections) nodeInfo).getNumConnectionsOnNode();
        } else if (nodeInfo instanceof ImmutableArcInst) {
            return 1;
        } else if (nodeInfo instanceof PortConns) {
            return ((PortConns) nodeInfo).getNumConnections();
        } else {
            return 0;
        }
    }

    @Override
    public List<ImmutableArcInst> getConnectionsOnNode(BitSet headEnds, ImmutableNodeInst n) {
        Object nodeInfo = getNodeInfo(n);
        if (nodeInfo instanceof NodeConnections) {
            return ((NodeConnections) nodeInfo).getConnectionsOnNode(headEnds, n);
        } else if (nodeInfo instanceof ImmutableArcInst) {
            ImmutableArcInst a = (ImmutableArcInst) nodeInfo;
            if (headEnds != null) {
                headEnds.clear();
                if (a.headNodeId == n.nodeId) {
                    assert a.headPortId.externalId.isEmpty();
                    headEnds.set(0);
                } else {
                    assert a.tailNodeId == n.nodeId;
                }
            }
            return Collections.singletonList(a);
        } else if (nodeInfo instanceof PortConns) {
            return ((PortConns) nodeInfo).getConnections(headEnds, n.nodeId, n.protoId.newPortId(""));
        } else {
            if (headEnds != null) {
                headEnds.clear();
            }
            return Collections.emptyList();
        }
    }

    /**
     * Returns true of there are Connections on specified ImmutableNodeInst
     * connected either to specified port or to all ports
     *
     * @param n specified ImmutableNodeInst
     * @param portId specified port or null
     * @return true if there are Connections on specified ImmutableNodeInst amd
     * specified port.
     * @throws NullPointerException if n or portId is null
     */
    @Override
    public boolean hasConnectionsOnPort(ImmutableNodeInst n, PortProtoId portId) {
        if (portId.parentId != n.protoId) {
            throw new IllegalArgumentException();
        }
        Object nodeInfo = getNodeInfo(n);
        if (nodeInfo instanceof NodeConnections) {
            return ((NodeConnections) nodeInfo).hasConnectionsOnPort(portId);
        } else if (nodeInfo instanceof ImmutableArcInst) {
            return portId.externalId.isEmpty();
        } else if (nodeInfo instanceof PortConns) {
            return portId.externalId.isEmpty() && ((PortConns) nodeInfo).hasConnections();
        } else {
            return false;
        }
    }

    /**
     * Method to return the number of Connections on specified port of specified
     * ImmutableNodeInst.
     *
     * @param n specified ImmutableNodeInst
     * @param portId specified port or null
     * @return the number of Connections on specified port of specified
     * ImmutableNodeInst.
     * @throws NullPointerException if n or portId is null
     * @throws IllegalArgumetException if node inst is not linked to this
     * CellRevision
     */
    @Override
    public int getNumConnectionsOnPort(ImmutableNodeInst n, PortProtoId portId) {
        if (portId.parentId != n.protoId) {
            throw new IllegalArgumentException();
        }
        Object nodeInfo = getNodeInfo(n);
        if (nodeInfo instanceof NodeConnections) {
            return ((NodeConnections) nodeInfo).getNumConnectionsOnPort(portId);
        } else if (nodeInfo instanceof ImmutableArcInst) {
            return portId.externalId.isEmpty() ? 1 : 0;
        } else if (nodeInfo instanceof PortConns) {
            return portId.externalId.isEmpty() ? ((PortConns) nodeInfo).getNumConnections() : 0;
        } else {
            return 0;
        }
    }

    /**
     * Method to return a list of arcs connected to speciefed or all ports of
     * specified ImmutableNodeInst.
     *
     * @param headEnds true if i-th arc connects by head end
     * @param n specified ImmutableNodeInst
     * @param portId specified port or null
     * @return a List of connected ImmutableArcInsts
     * @throws NullPointerException if n or portId is null
     */
    @Override
    public List<ImmutableArcInst> getConnectionsOnPort(BitSet headEnds, ImmutableNodeInst n, PortProtoId portId) {
        if (portId.parentId != n.protoId) {
            throw new IllegalArgumentException();
        }
        Object nodeInfo = getNodeInfo(n);
        if (nodeInfo instanceof NodeConnections) {
            return ((NodeConnections) nodeInfo).getConnectionsOnPort(headEnds, n.nodeId, portId);
        } else if (nodeInfo instanceof ImmutableArcInst) {
            ImmutableArcInst a = (ImmutableArcInst) nodeInfo;
            if (portId.externalId.isEmpty()) {
                if (headEnds != null) {
                    headEnds.clear();
                    if (a.headNodeId == n.nodeId) {
                        assert a.headPortId == portId;
                        assert a.tailNodeId != n.nodeId;
                        headEnds.set(0);
                    } else {
                        assert a.tailNodeId == n.nodeId;
                        assert a.tailPortId == portId;
                        assert a.headNodeId != n.nodeId;
                    }
                }
                return Collections.singletonList(a);
            }
        } else if (nodeInfo instanceof PortConns) {
            PortConns pc = (PortConns) nodeInfo;
            if (portId.externalId.isEmpty()) {
                return pc.getConnections(headEnds, n.nodeId, portId);
            }
        }
        if (headEnds != null) {
            headEnds.clear();
        }
        return Collections.emptyList();
    }

    /**
     * Returns true of there are Exports on specified NodeInst.
     *
     * @param originalNode specified ImmutableNodeInst.
     * @return true if there are Exports on specified NodeInst.
     */
    @Override
    public boolean hasExportsOnNode(ImmutableNodeInst originalNode) {
        Object nodeInfo = getNodeInfo(originalNode);
        if (nodeInfo instanceof NodeConnections) {
            return ((NodeConnections) nodeInfo).hasExportsOnNode();
        } else if (nodeInfo instanceof ImmutableExport) {
            return true;
        } else if (nodeInfo instanceof PortConns) {
            return ((PortConns) nodeInfo).hasExportsOnPort();
        } else {
            return false;
        }
    }

    /**
     * Method to return the number of Exports on specified NodeInst.
     *
     * @param originalNode specified ImmutableNodeInst.
     * @return the number of Exports on specified NodeInst.
     */
    @Override
    public int getNumExportsOnNode(ImmutableNodeInst originalNode) {
        Object nodeInfo = getNodeInfo(originalNode);
        if (nodeInfo instanceof NodeConnections) {
            return ((NodeConnections) nodeInfo).getNumExportsOnNode();
        } else if (nodeInfo instanceof ImmutableExport) {
            return 1;
        } else if (nodeInfo instanceof PortConns) {
            return ((PortConns) nodeInfo).getNumExportsOnPort();
        } else {
            return 0;
        }
    }

    /**
     * Method to return an Iterator over all ImmutableExports on specified
     * NodeInst.
     *
     * @param originalNode specified ImmutableNodeInst.
     * @return an Iterator over all ImmutableExports on specified NodeInst.
     */
    @Override
    public Iterator<ImmutableExport> getExportsOnNode(ImmutableNodeInst originalNode) {
        Object nodeInfo = getNodeInfo(originalNode);
        if (nodeInfo instanceof NodeConnections) {
            return ((NodeConnections) nodeInfo).getExportsOnNode();
        } else if (nodeInfo instanceof ImmutableExport) {
            return ArrayIterator.singletonIterator((ImmutableExport) nodeInfo);
        } else if (nodeInfo instanceof PortConns) {
            return ((PortConns) nodeInfo).getExportsOnPort();
        } else {
            return ArrayIterator.emptyIterator();
        }
    }

    /**
     * Returns true of there are Exports on specified port of specified
     * ImmutableNodeInst.
     *
     * @param originalNode specified ImmutableNodeInst.
     * @param portId specified port
     * @return true if there are Exports on specified NodeInst.
     * @throws NullPointerException if n or portId is null
     * @throws IllegalArgumetException if node inst is not linked to this
     * CellRevision
     */
    @Override
    public boolean hasExportsOnPort(ImmutableNodeInst originalNode, PortProtoId portId) {
        if (portId.parentId != originalNode.protoId) {
            throw new IllegalArgumentException();
        }
        Object nodeInfo = getNodeInfo(originalNode);
        if (nodeInfo instanceof NodeConnections) {
            return ((NodeConnections) nodeInfo).hasExportsOnPort(portId);
        } else if (nodeInfo instanceof ImmutableExport) {
            return portId.externalId.isEmpty();
        } else if (nodeInfo instanceof PortConns) {
            return portId.externalId.isEmpty() && ((PortConns) nodeInfo).hasExportsOnPort();
        } else {
            return false;
        }
    }

    /**
     * Method to return the number of Exports on specified port of specified
     * ImmutableNodeInst.
     *
     * @param originalNode specified ImmutableNodeInst.
     * @param portId specified port
     * @return the number of Exports on specified NodeInst.
     * @throws NullPointerException if n or portId is null
     * @throws IllegalArgumetException if node inst is not linked to this
     * CellRevision
     */
    @Override
    public int getNumExportsOnPort(ImmutableNodeInst originalNode, PortProtoId portId) {
        if (portId.parentId != originalNode.protoId) {
            throw new IllegalArgumentException();
        }
        Object nodeInfo = getNodeInfo(originalNode);
        if (nodeInfo instanceof NodeConnections) {
            return ((NodeConnections) nodeInfo).getNumExportsOnPort(portId);
        } else if (nodeInfo instanceof ImmutableExport) {
            return portId.externalId.isEmpty() ? 1 : 0;
        } else if (nodeInfo instanceof PortConns) {
            return portId.externalId.isEmpty() ? ((PortConns) nodeInfo).getNumExportsOnPort() : 0;
        } else {
            return 0;
        }
    }

    /**
     * Method to return an Iterator over all ImmutableExports on specified port
     * of specified ImmutableNodeInst.
     *
     * @param originalNode specified ImmutableNodeInst.
     * @param portId specified port
     * @return an Iterator over all ImmutableExports on specified NodeInst.
     * @throws NullPointerException if n or portId is null
     * @throws IllegalArgumetException if node inst is not linked to this
     * CellRevision
     */
    @Override
    public Iterator<ImmutableExport> getExportsOnPort(ImmutableNodeInst originalNode, PortProtoId portId) {
        if (portId.parentId != originalNode.protoId) {
            throw new IllegalArgumentException();
        }
        Object nodeInfo = getNodeInfo(originalNode);
        if (nodeInfo instanceof NodeConnections) {
            return ((NodeConnections) nodeInfo).getExportsOnPort(portId);
        } else if (nodeInfo instanceof ImmutableExport) {
            if (portId.externalId.isEmpty()) {
                return ArrayIterator.singletonIterator((ImmutableExport) nodeInfo);
            }
        } else if (nodeInfo instanceof PortConns) {
            if (portId.externalId.isEmpty()) {
                return ((PortConns) nodeInfo).getExportsOnPort();
            }
        }
        return ArrayIterator.emptyIterator();
    }

    private Object getNodeInfo(ImmutableNodeInst n) {
        int nodeId = n.nodeId;
        if (getNodeById(nodeId) != n) {
            throw new IllegalArgumentException();
        }
        Object[][] connections = nodeConnectionsRef.get();
        if (connections != null) {
            return connections[nodeId >> LOW_BITS][nodeId & LOW_MASK];
        } else {
            return makeNodeInfo(nodeId);
        }
    }

    private Object makeNodeInfo(int nodeId) {
        BlockBuffer[] blockBuffers = allocBlockBuffers(getMaxNodeId());
        for (ImmutableArcInst a : arcs) {
            newArc(blockBuffers, a);
        }
        for (ImmutableExport e : exports) {
            newExport(blockBuffers, e);
        }
        return updateBlocks(null, blockBuffers, this, emptyBlocks)[nodeId >> LOW_BITS][nodeId & LOW_MASK];
    }

    private static void putKilled(BlockBuffer[] blockBuffers, Map<Integer, List<ImmutableElectricObject>> killedConnectivity, CellRevisionConn newCellRevision, int nodeId, ImmutableElectricObject obj) {
        if (newCellRevision.getNodeById(nodeId) != null) {
            List<ImmutableElectricObject> list = killedConnectivity.get(nodeId);
            if (list == null) {
                list = new ArrayList<ImmutableElectricObject>();
                killedConnectivity.put(nodeId, list);
            }
            list.add(obj);
            getBuffer(blockBuffers, nodeId).hasKilledObjs = true;
        }
    }

    @Override
    CellRevision lowLevelWith(ImmutableCell d,
            ImmutableNodeInst.Iterable nodes,
            ImmutableArcInst.Iterable arcs, int[] arcIndex,
            ImmutableExport.Iterable exports, int[] exportIndex,
            BitSet techUsages, CellUsageInfo[] cellUsages,
            BitSet definedExports, int definedExportsLength, BitSet deletedExports) {
        CellRevisionConn newCellRevision = new CellRevisionConn(d,
                nodes,
                arcs, arcIndex,
                exports, exportIndex,
                techUsages, cellUsages,
                definedExports, definedExportsLength, deletedExports);
        Object[][] oldBlocks = nodeConnectionsRef.get();
        if (oldBlocks != null && d.cellId == this.d.cellId) { // Don't reuse connectivity after rename
            Map<Integer, List<ImmutableElectricObject>> killedConnectivity = new HashMap<Integer, List<ImmutableElectricObject>>();
            int maxNodeId = newCellRevision.getMaxNodeId();
            BlockBuffer[] blockBuffers = allocBlockBuffers(maxNodeId);
            int maxArcId = Math.max(this.getMaxArcId(), newCellRevision.getMaxArcId());
            for (int arcId = 0; arcId <= maxArcId; arcId++) {
                ImmutableArcInst oldA = this.getArcById(arcId);
                ImmutableArcInst newA = newCellRevision.getArcById(arcId);
                if (oldA != newA) {
                    if (oldA != null) {
                        putKilled(blockBuffers, killedConnectivity, newCellRevision, oldA.tailNodeId, oldA);
                        if (oldA.headNodeId != oldA.tailNodeId) {
                            putKilled(blockBuffers, killedConnectivity, newCellRevision, oldA.headNodeId, oldA);
                        }
                    }
                    newArc(blockBuffers, newA);
                }
            }
            int maxChronIndex = Math.max(this.getMaxExportChronIndex(), newCellRevision.getMaxExportChronIndex());
            CellId cellId = d.cellId;
            assert newCellRevision.d.cellId == cellId;
            for (int chronIndex = 0; chronIndex <= maxChronIndex; chronIndex++) {
                ExportId exportId = cellId.getPortId(chronIndex);
                ImmutableExport oldE = this.getExport(exportId);
                ImmutableExport newE = newCellRevision.getExport(exportId);
                if (oldE != newE) {
                    if (oldE != null) {
                        putKilled(blockBuffers, killedConnectivity, newCellRevision, oldE.originalNodeId, oldE);
                    }
                    newExport(blockBuffers, newE);
                }
            }
            newCellRevision.updateBlocks(killedConnectivity, blockBuffers, this, oldBlocks);
        }
        return newCellRevision;
    }

    private class ConComparator implements Comparator<Integer> {

        @Override
        public int compare(Integer con1, Integer con2) {
            ImmutableArcInst a1 = getArcById(con1 >> 1);
            ImmutableArcInst a2 = getArcById(con2 >> 1);
            boolean end1 = (con1 & 1) != 0;
            boolean end2 = (con2 & 1) != 0;
            int nodeId1 = end1 ? a1.headNodeId : a1.tailNodeId;
            int nodeId2 = end2 ? a2.headNodeId : a2.tailNodeId;
            assert nodeId1 == nodeId2;
            PortProtoId portId1 = end1 ? a1.headPortId : a1.tailPortId;
            PortProtoId portId2 = end2 ? a2.headPortId : a2.tailPortId;
            if (portId1.chronIndex < portId2.chronIndex) {
                return -1;
            }
            if (portId1.chronIndex > portId2.chronIndex) {
                return +1;
            }
            if (con1 < con2) {
                return -1;
            }
            if (con1 > con2) {
                return +1;
            }
            return 0;
        }
    }
    private static final Comparator<ImmutableExport> ExpComparator = new Comparator<ImmutableExport>() {
        @Override
        public int compare(ImmutableExport e1, ImmutableExport e2) {
            assert e1.originalNodeId == e2.originalNodeId;
            if (e1.originalPortId.chronIndex < e2.originalPortId.chronIndex) {
                return -1;
            } else if (e1.originalPortId.chronIndex > e2.originalPortId.chronIndex) {
                return +1;
            } else if (e1.exportId.chronIndex < e2.exportId.chronIndex) {
                return -1;
            } else if (e1.exportId.chronIndex > e2.exportId.chronIndex) {
                return +1;
            } else {
                return 0;
            }
        }
    };

    private Object[][] updateBlocks(Map<Integer, List<ImmutableElectricObject>> killedConnectivity,
            BlockBuffer[] blockBuffers, CellRevision oldCellRevision, Object[][] oldBlocks) {
        Object[][] newBlocks = new Object[blockBuffers.length][];
        for (int bi = 0; bi < newBlocks.length; bi++) {
            Object[] oldBlock = bi < oldBlocks.length ? oldBlocks[bi] : emptyBlock;
            BlockBuffer bb = blockBuffers[bi];
            if (bb == null) {
                newBlocks[bi] = oldBlock;
                continue;
            }
            Object[] newBlock = new Object[LOW_SIZE];
            boolean newBlockIsEmpty = true;
            for (int i = 0; i < LOW_SIZE; i++) {
                int nodeId = (bi << LOW_BITS) + i;
                Object nodeInfo = oldBlock[i];
                ImmutableList<ImmutableElectricObject> newObjs = bb.newObjs[i];
                List<ImmutableElectricObject> killedList = null;
                if (bb.hasKilledObjs) {
                    killedList = killedConnectivity.get(nodeId);
                }
                ImmutableNodeInst n = getNodeById(nodeId);
                if (n == null) {
                    continue;
                }
                ImmutableNodeInst oldN = oldCellRevision != this ? oldCellRevision.getNodeById(nodeId) : n;
                if (newObjs == null && killedList == null) {
                    if (nodeInfo != null) {
                        assert oldN.protoId == n.protoId;
                        newBlockIsEmpty = false;
                    }
                    newBlock[i] = nodeInfo;
                    continue;
                }
                List<Integer> newCons = new ArrayList<Integer>();
                List<ImmutableExport> newExports = new ArrayList<ImmutableExport>();
                Set<Object> killed = killedList != null ? new HashSet<Object>(killedList) : Collections.emptySet();
                if (nodeInfo instanceof NodeConnections) {
                    NodeConnections nc = (NodeConnections) nodeInfo;
                    BitSet arcEnds = new BitSet();
                    List<ImmutableArcInst> arcs = Collections.emptyList();
                    if (oldN != null) {
                        arcs = nc.getConnectionsOnNode(arcEnds, oldN);
                    }
                    for (int connIndex = 0; connIndex < arcs.size(); connIndex++) {
                        ImmutableArcInst a = arcs.get(connIndex);
                        if (!killed.contains(a)) {
                            int arcId = a.arcId;
                            newCons.add(arcEnds.get(connIndex) ? (arcId << 1) | 1 : (arcId << 1));
                        }
                    }
                    for (Iterator<ImmutableExport> eit = nc.getExportsOnNode(); eit.hasNext();) {
                        ImmutableExport e = eit.next();
                        if (!killed.contains(e)) {
                            newExports.add(e);
                        }
                    }
                } else if (nodeInfo instanceof ImmutableArcInst) {
                    ImmutableArcInst a = (ImmutableArcInst) nodeInfo;
                    if (killed.isEmpty()) {
                        int arcId = a.arcId;
                        boolean end = a.headNodeId == nodeId && a.headPortId.externalId.isEmpty();
                        newCons.add(end ? (arcId << 1) | 1 : (arcId << 1));
                    } else {
                        assert killed.contains(a) && killed.size() == 1;
                    }
                } else if (nodeInfo instanceof ImmutableExport) {
                    ImmutableExport e = (ImmutableExport) nodeInfo;
                    if (killed.isEmpty()) {
                        newExports.add(e);
                    } else {
                        assert killed.contains(e) && killed.size() == 1;
                    }
                } else if (nodeInfo instanceof PortConns) {
                    PortConns pc = (PortConns) nodeInfo;
                    if (pc.hasConnections()) {
                        BitSet arcHeads = new BitSet();
                        List<ImmutableArcInst> arcs = Collections.emptyList();
                        if (oldN != null) {
                            arcs = pc.getConnections(arcHeads, nodeId, oldN.protoId.newPortId(""));
                        }
                        for (int connIndex = 0; connIndex < arcs.size(); connIndex++) {
                            ImmutableArcInst a = arcs.get(connIndex);
                            if (!killed.contains(a)) {
                                int arcId = a.arcId;
                                newCons.add(arcHeads.get(connIndex) ? (arcId << 1) | 1 : (arcId << 1));
                            }
                        }
                    }
                    if (pc.hasExportsOnPort()) {
                        for (Iterator<ImmutableExport> eit = pc.getExportsOnPort(); eit.hasNext();) {
                            ImmutableExport e = eit.next();
                            if (!killed.contains(e)) {
                                newExports.add(e);
                            }
                        }
                    }
                } else {
                    assert nodeInfo == null;
                }
                if (oldN == null || oldN.protoId != n.protoId) {
                    assert newCons.isEmpty() && newExports.isEmpty();
                }
                while (newObjs != null) {
                    ImmutableElectricObject o = newObjs.getFirst();
                    if (o instanceof ImmutableArcInst) {
                        ImmutableArcInst a = (ImmutableArcInst) o;
                        if (a.tailNodeId == nodeId) {
                            newCons.add(a.arcId << 1);
                        }
                        if (a.headNodeId == nodeId) {
                            newCons.add((a.arcId << 1) | 1);
                        }
                    } else if (o instanceof ImmutableExport) {
                        newExports.add((ImmutableExport) o);
                    }
                    newObjs = newObjs.getTail();
                }
                int maxChronIndex = -1;
                boolean onEmptyPort = true; // All connections to the node are on port with empty name
                boolean withoutLoops = true; // There is no arc with same tail PortInst and head PortInst
                Object newNodeInfo = null;
                if (!newCons.isEmpty() || !newExports.isEmpty()) {
                    newBlockIsEmpty = false;
                    ImmutableArcInst[] bArcs;
                    BitSet bArcHeads;
                    if (newCons.isEmpty()) {
                        bArcs = ImmutableArcInst.NULL_ARRAY;
                        bArcHeads = CellRevision.EMPTY_BITSET;
                    } else if (newCons.size() == 1) {
                        int con = newCons.get(0).intValue();
                        ImmutableArcInst a = getArcById(con >> 1);
                        withoutLoops = withoutLoops && (a.tailNodeId != a.headNodeId || a.tailPortId != a.headPortId);
                        if ((con & 1) != 0) {
                            maxChronIndex = Math.max(maxChronIndex, a.headPortId.chronIndex);
                            onEmptyPort = onEmptyPort && a.headPortId.externalId.isEmpty();
                            bArcHeads = new BitSet();
                            bArcHeads.set(0);
                            bArcs = new ImmutableArcInst[]{a};
                        } else {
                            maxChronIndex = Math.max(maxChronIndex, a.tailPortId.chronIndex);
                            onEmptyPort = onEmptyPort && a.tailPortId.externalId.isEmpty();
                            bArcs = new ImmutableArcInst[]{a};
                            bArcHeads = CellRevision.EMPTY_BITSET;
                        }
                    } else {
                        Collections.sort(newCons, new ConComparator());
                        ImmutableArcInst[] arr = new ImmutableArcInst[newCons.size()];
                        BitSet bs = new BitSet();
                        for (int j = 0; j < newCons.size(); j++) {
                            int con = newCons.get(j).intValue();
                            ImmutableArcInst a = getArcById(con >> 1);
                            arr[j] = a;
                            withoutLoops = withoutLoops && (a.tailNodeId != a.headNodeId || a.tailPortId != a.headPortId);
                            if ((con & 1) != 0) {
                                maxChronIndex = Math.max(maxChronIndex, a.headPortId.chronIndex);
                                onEmptyPort = onEmptyPort && a.headPortId.externalId.isEmpty();
                                bs.set(j);
                            } else {
                                maxChronIndex = Math.max(maxChronIndex, a.tailPortId.chronIndex);
                                onEmptyPort = onEmptyPort && a.tailPortId.externalId.isEmpty();
                            }
                        }
                        bArcs = arr;
                        bArcHeads = bs.isEmpty() ? CellRevision.EMPTY_BITSET : bs;
                    }
                    ImmutableExport[] bExports;
                    if (newExports.isEmpty()) {
                        bExports = ImmutableExport.NULL_ARRAY;
                    } else if (newExports.size() == 1) {
                        ImmutableExport e = newExports.get(0);
                        maxChronIndex = Math.max(maxChronIndex, e.originalPortId.chronIndex);
                        onEmptyPort = onEmptyPort && e.originalPortId.externalId.isEmpty();
                        bExports = new ImmutableExport[]{e};
                    } else {
                        Collections.sort(newExports, ExpComparator);
                        for (ImmutableExport e : newExports) {
                            maxChronIndex = Math.max(maxChronIndex, e.originalPortId.chronIndex);
                            onEmptyPort = onEmptyPort && e.originalPortId.externalId.isEmpty();
                        }
                        bExports = newExports.toArray(ImmutableExport.NULL_ARRAY);
                    }
                    if (!onEmptyPort) {
                        Object[] portsInfo = new Object[maxChronIndex + 1];
                        int conI = 0;
                        int expI = 0;
                        for (int chronIndex = 0; chronIndex < portsInfo.length; chronIndex++) {
                            boolean withoutLoopsP = true;
                            int conJ = conI;
                            while (conJ < bArcs.length && (bArcHeads.get(conJ) ? bArcs[conJ].headPortId : bArcs[conJ].tailPortId).chronIndex == chronIndex) {
                                ImmutableArcInst a = bArcs[conJ];
                                if (a.headNodeId == a.tailNodeId && a.headPortId == a.tailPortId) {
                                    withoutLoopsP = false;
                                }
                                conJ++;
                            }
                            int expJ = expI;
                            while (expJ < bExports.length && bExports[expJ].originalPortId.chronIndex == chronIndex) {
                                expJ++;
                            }
                            Object newPortInfo;
                            if (withoutLoopsP) {
                                switch (expJ - expI) {
                                    case 0:
                                        switch (conJ - conI) {
                                            case 0:
                                                newPortInfo = null;
                                                break;
                                            case 1:
                                                newPortInfo = bArcs[conI];
                                                break;
                                            case 2:
                                                newPortInfo = new PortConA2E0(bArcs[conI], bArcs[conI + 1]);
                                                break;
                                            case 3:
                                                newPortInfo = new PortConA3E0(bArcs[conI], bArcs[conI + 1], bArcs[conI + 2]);
                                                break;
                                            default:
                                                newPortInfo = new PortConANE0(Arrays.copyOfRange(bArcs, conI, conJ));
                                        }
                                        break;
                                    case 1:
                                        switch (conJ - conI) {
                                            case 0:
                                                newPortInfo = bExports[expI];
                                                break;
                                            case 1:
                                                newPortInfo = new PortConA1E1(bArcs[conI], bExports[expI]);
                                                break;
                                            case 2:
                                                newPortInfo = new PortConA2E1(bArcs[conI], bArcs[conI + 1], bExports[expI]);
                                                break;
                                            case 3:
                                                newPortInfo = new PortConA3E1(bArcs[conI], bArcs[conI + 1], bArcs[conI + 2], bExports[expI]);
                                                break;
                                            default:
                                                newPortInfo = new PortConANE1(Arrays.copyOfRange(bArcs, conI, conJ), bExports[expI]);
                                        }
                                        break;
                                    default:
                                        ImmutableArcInst[] pArcs = conI < conJ ? Arrays.copyOfRange(bArcs, conI, conJ) : ImmutableArcInst.NULL_ARRAY;
                                        ImmutableExport[] pExports = expI < expJ ? Arrays.copyOfRange(bExports, expI, expJ) : ImmutableExport.NULL_ARRAY;
                                        newPortInfo = new PortConANEN(pArcs, pExports);
                                }
                            } else {
                                ImmutableArcInst[] pArcs = conI < conJ ? Arrays.copyOfRange(bArcs, conI, conJ) : ImmutableArcInst.NULL_ARRAY;
                                BitSet pArcHeads = new BitSet();
                                for (int c = conI; c < conJ; c++) {
                                    pArcHeads.set(c - conI, bArcHeads.get(c));
                                }
                                if (pArcHeads.isEmpty()) {
                                    pArcHeads = EMPTY_BITSET;
                                }
                                ImmutableExport[] pExports = expI < expJ ? Arrays.copyOfRange(bExports, expI, expJ) : ImmutableExport.NULL_ARRAY;
                                newPortInfo = new PortConALEN(pArcs, pArcHeads, pExports);
                            }
                            portsInfo[chronIndex] = newPortInfo;
                            conI = conJ;
                            expI = expJ;
                        }
                        newNodeInfo = new NodeConnections(portsInfo, n);
                    } else if (withoutLoops) {
                        switch (bExports.length) {
                            case 0:
                                switch (bArcs.length) {
                                    case 1:
                                        newNodeInfo = bArcs[0];
                                        break;
                                    case 2:
                                        newNodeInfo = new PortConA2E0(bArcs[0], bArcs[1]);
                                        break;
                                    case 3:
                                        newNodeInfo = new PortConA3E0(bArcs[0], bArcs[1], bArcs[2]);
                                        break;
                                    default:
                                        newNodeInfo = new PortConANE0(bArcs);
                                }
                                break;
                            case 1:
                                switch (bArcs.length) {
                                    case 0:
                                        newNodeInfo = bExports[0];
                                        break;
                                    case 1:
                                        newNodeInfo = new PortConA1E1(bArcs[0], bExports[0]);
                                        break;
                                    case 2:
                                        newNodeInfo = new PortConA2E1(bArcs[0], bArcs[1], bExports[0]);
                                        break;
                                    case 3:
                                        newNodeInfo = new PortConA3E1(bArcs[0], bArcs[1], bArcs[2], bExports[0]);
                                        break;
                                    default:
                                        newNodeInfo = new PortConANE1(bArcs, bExports[0]);
                                }
                                break;
                            default:
                                newNodeInfo = new PortConANEN(bArcs, bExports);
                        }
                    } else {
                        newNodeInfo = new PortConALEN(bArcs, bArcHeads, bExports);
                    }
                }
                newBlock[i] = newNodeInfo;
            }
            newBlocks[bi] = newBlockIsEmpty ? emptyBlock : newBlock;
        }
        nodeConnectionsRef = new SoftReference(newBlocks);
        return newBlocks;
    }

    private static BlockBuffer[] allocBlockBuffers(int maxNodeId) {
        return new BlockBuffer[(maxNodeId >> LOW_BITS) + 1];
    }

    private static void newArc(BlockBuffer[] blockBuffers, ImmutableArcInst newA) {
        if (newA != null) {
            getBuffer(blockBuffers, newA.tailNodeId).putObj(newA.tailNodeId, newA);
            if (newA.headNodeId != newA.tailNodeId) {
                getBuffer(blockBuffers, newA.headNodeId).putObj(newA.headNodeId, newA);
            }
        }
    }

    private static void newExport(BlockBuffer[] blockBuffers, ImmutableExport newE) {
        if (newE != null) {
            getBuffer(blockBuffers, newE.originalNodeId).putObj(newE.originalNodeId, newE);
        }
    }

    private static BlockBuffer getBuffer(BlockBuffer[] blockBuffers, int nodeId) {
        int bi = nodeId >> LOW_BITS;
        BlockBuffer b = blockBuffers[bi];
        if (b != null) {
            return b;
        }
        BlockBuffer newB = new BlockBuffer();
        blockBuffers[bi] = newB;
        return newB;
    }

    /**
     * Compute memory consumption
     * @param objSize ObjSDize in this JVM, or null to count objects
     * @param restore restore connectivity data i fnecessary.
     * @return size in bytes, or object count
     */
    @Override
    public long getConnectivityMemorySize(ObjSize objSize, boolean restore) {
        long s = 0;
        if (restore && !nodes.isEmpty()) {
            makeNodeInfo(0);
        }
        Object[][] connections = nodeConnectionsRef.get();
        if (connections != null) {
            s += objSize.sizeOf(connections);
            for (Object[] block : connections) {
                if (block == emptyBlock) {
                    continue;
                }
                s += objSize.sizeOf(block);
                for (Object nodeInfo : block) {
                    if (nodeInfo instanceof NodeConnections) {
                        s += ((NodeConnections) nodeInfo).getMemorySize(objSize);
                    } else if (nodeInfo instanceof PortConns) {
                        s += ((PortConns) nodeInfo).getMemorySize(objSize);
                    }
                }
            }
        }
        return s;
    }

    @Override
    public void check() {
        super.check();
        Object[][] blocks = nodeConnectionsRef.get();
        if (blocks == null) {
            return;
        }
        assert blocks.length == (getMaxNodeId() >> LOW_BITS) + 1;
        for (int bi = 0; bi < blocks.length; bi++) {
            Object[] b = blocks[bi];
            if (b != emptyBlock) {
                boolean nonNull = false;
                for (int i = 0; i < LOW_SIZE; i++) {
                    Object nodeInfo = b[i];
                    if (nodeInfo == null) {
                        continue;
                    }
                    int nodeId = (bi << LOW_BITS) + i;
                    ImmutableNodeInst n = getNodeById(nodeId);
                    assert n != null;
                    nonNull = true;
                    if (nodeInfo instanceof NodeConnections) {
                        NodeConnections nc = (NodeConnections) nodeInfo;
                        nc.check(n);
                    } else if (nodeInfo instanceof ImmutableArcInst) {
                        ImmutableArcInst a = (ImmutableArcInst) nodeInfo;
                        assert a.tailNodeId == nodeId && a.headNodeId != nodeId && a.tailPortId.parentId == n.protoId && a.tailPortId.externalId.isEmpty()
                                || a.headNodeId == nodeId && a.tailNodeId != nodeId && a.headPortId.parentId == n.protoId && a.headPortId.externalId.isEmpty();
                    } else if (nodeInfo instanceof ImmutableExport) {
                        ImmutableExport e = (ImmutableExport) nodeInfo;
                        assert e.originalNodeId == nodeId && e.originalPortId.parentId == n.protoId && e.originalPortId.externalId.isEmpty();
                    } else if (nodeInfo instanceof PortConns) {
                        PortConns pc = (PortConns) nodeInfo;
                        pc.check(n, n.protoId.newPortId(""));
                    } else {
                        assert false;
                    }
                }
                assert nonNull;
            }
        }
        // general connectivity check
        checkConnectivity();
    }

    private static class NodeConnections {

        private final Object[] portsInfo;
        private final int numCon, numExp;

        private NodeConnections(Object[] portsInfo, ImmutableNodeInst n) {
            this.portsInfo = portsInfo;
            int conI = 0;
            int expI = 0;
            for (int chronIndex = 0; chronIndex < portsInfo.length; chronIndex++) {
                Object portInfo = portsInfo[chronIndex];
                if (portInfo instanceof ImmutableArcInst) {
                    ImmutableArcInst a = (ImmutableArcInst) portInfo;
                    boolean end = a.headNodeId == n.nodeId && a.headPortId.chronIndex == chronIndex;
                    assert (end ? a.headNodeId : a.tailNodeId) == n.nodeId;
                    PortProtoId portId = end ? a.headPortId : a.tailPortId;
                    assert portId.parentId == n.protoId;
                    assert portId.chronIndex == chronIndex;
                    conI++;
                } else if (portInfo instanceof ImmutableExport) {
                    ImmutableExport e = (ImmutableExport) portInfo;
                    assert e.originalNodeId == n.nodeId;
                    assert e.originalPortId.parentId == n.protoId;
                    assert e.originalPortId.chronIndex == chronIndex;
                    expI++;
                } else if (portInfo instanceof PortConns) {
                    PortConns pc = (PortConns) portInfo;
                    BitSet pArcEnds = new BitSet();
                    List<ImmutableArcInst> pArcs = pc.getConnections(pArcEnds, n.nodeId, n.protoId.getPortId(chronIndex));
                    for (int i = 0; i < pArcs.size(); i++) {
                        ImmutableArcInst a = pArcs.get(i);
                        boolean end = a.headNodeId == n.nodeId && a.headPortId.chronIndex == chronIndex;
                        if (end && a.tailNodeId == n.nodeId && a.tailPortId.chronIndex == chronIndex && i + 1 < pArcs.size() && pArcs.get(i + 1) == a) {
                            end = false;
                        }
                        assert pArcEnds.get(i) == end;
                        assert (end ? a.headNodeId : a.tailNodeId) == n.nodeId;
                        PortProtoId portId = end ? a.headPortId : a.tailPortId;
                        assert portId.parentId == n.protoId;
                        assert portId.chronIndex == chronIndex;
                        conI++;
                    }
                    for (Iterator<ImmutableExport> eit = pc.getExportsOnPort(); eit.hasNext();) {
                        ImmutableExport e = eit.next();
                        assert e.originalNodeId == n.nodeId;
                        assert e.originalPortId.parentId == n.protoId;
                        assert e.originalPortId.chronIndex == chronIndex;
                        expI++;
                    }
                }
            }
            numCon = conI;
            numExp = expI;
        }

        /**
         * Returns true of there are Connections on specified ImmutableNodeInst
         *
         * @return true if there are Connections on specified ImmutableNodeInst
         * amd specified port.
         */
        private boolean hasConnectionsOnNode() {
            return numCon != 0;
        }

        /**
         * Method to return the number of Connections on specified
         * ImmutableNodeInst.
         *
         * @return the number of Connections on specified ImmutableNodeInst.
         */
        private int getNumConnectionsOnNode() {
            return numCon;
        }

        private List<ImmutableArcInst> getConnectionsOnNode(BitSet arcEnds, ImmutableNodeInst n) {
            if (arcEnds != null) {
                arcEnds.clear();
            }
            if (numCon == 0) {
                return Collections.emptyList();
            }
            ImmutableArcInst[] arcs = new ImmutableArcInst[numCon];
            int conI = 0;
            for (int chronIndex = 0; chronIndex < portsInfo.length; chronIndex++) {
                Object portInfo = portsInfo[chronIndex];
                if (portInfo instanceof ImmutableArcInst) {
                    ImmutableArcInst a = (ImmutableArcInst) portInfo;
                    arcs[conI] = a;
                    if (arcEnds != null && a.headNodeId == n.nodeId && a.headPortId.chronIndex == chronIndex) {
                        arcEnds.set(conI);
                    }
                    conI++;
                } else if (portInfo instanceof PortConns) {
                    PortConns pc = (PortConns) portInfo;
                    BitSet arcEndsP = arcEnds != null ? new BitSet() : null;
                    List<ImmutableArcInst> arcsP = pc.getConnections(arcEndsP, n.nodeId, n.protoId.getPortId(chronIndex));
                    for (int i = 0; i < arcsP.size(); i++) {
                        arcs[conI] = arcsP.get(i);
                        if (arcEnds != null) {
                            arcEnds.set(conI, arcEndsP.get(i));
                        }
                        conI++;
                    }
                }
            }
            assert conI == numCon;
            return ImmutableArrayList.of(arcs);
        }

        /**
         * Returns true of there are Connections on specified ImmutableNodeInst
         * connected either to specified port
         *
         * @param portId specified port
         * @return true if there are Connections on specified ImmutableNodeInst
         * amd specified port.
         */
        private boolean hasConnectionsOnPort(PortProtoId portId) {
            int chronIndex = portId.chronIndex;
            if (chronIndex < portsInfo.length) {
                Object portInfo = portsInfo[chronIndex];
                if (portInfo instanceof ImmutableArcInst) {
                    return true;
                } else if (portInfo instanceof PortConns) {
                    return ((PortConns) portInfo).hasConnections();
                }
            }
            return false;
        }

        private int getNumConnectionsOnPort(PortProtoId portId) {
            int chronIndex = portId.chronIndex;
            if (chronIndex < portsInfo.length) {
                Object portInfo = portsInfo[chronIndex];
                if (portInfo instanceof ImmutableArcInst) {
                    return 1;
                } else if (portInfo instanceof PortConns) {
                    return ((PortConns) portInfo).getNumConnections();
                }
            }
            return 0;
        }

        private List<ImmutableArcInst> getConnectionsOnPort(BitSet arcEnds, int nodeId, PortProtoId portId) {
            if (arcEnds != null) {
                arcEnds.clear();
            }
            int chronIndex = portId.chronIndex;
            if (chronIndex < portsInfo.length) {
                Object portInfo = portsInfo[chronIndex];
                if (portInfo instanceof ImmutableArcInst) {
                    ImmutableArcInst a = (ImmutableArcInst) portInfo;
                    if (arcEnds != null) {
                        arcEnds.set(0, a.headNodeId == nodeId && a.headPortId == portId);
                    }
                    return Collections.singletonList(a);
                } else if (portInfo instanceof PortConns) {
                    return ((PortConns) portInfo).getConnections(arcEnds, nodeId, portId);
                }
            }
            return Collections.emptyList();
        }

        /**
         * Returns true of there are Exports on specified NodeInst.
         *
         * @param originalNode specified NodeInst.
         * @return true if there are Exports on specified NodeInst.
         */
        private boolean hasExportsOnNode() {
            return numExp != 0;
        }

        /**
         * Method to return the number of Exports on specified NodeInst.
         *
         * @return the number of Exports on specified NodeInst.
         */
        private int getNumExportsOnNode() {
            return numExp;
        }

        /**
         * Method to return an Iterator over all ImmutableExports on specified
         * NodeInst.
         *
         * @return an Iterator over all ImmutableExports on specified NodeInst.
         */
        private Iterator<ImmutableExport> getExportsOnNode() {
            if (numExp == 0) {
                return ArrayIterator.emptyIterator();
            }
            ImmutableExport[] exports = new ImmutableExport[numExp];
            int expI = 0;
            for (int chronIndex = 0; chronIndex < portsInfo.length; chronIndex++) {
                Object portInfo = portsInfo[chronIndex];
                if (portInfo instanceof ImmutableExport) {
                    exports[expI] = (ImmutableExport) portInfo;
                    expI++;
                } else if (portInfo instanceof PortConns) {
                    PortConns pc = (PortConns) portInfo;
                    for (Iterator<ImmutableExport> eit = pc.getExportsOnPort(); eit.hasNext();) {
                        exports[expI] = eit.next();
                        expI++;
                    }
                }
            }
            assert expI == numExp;
            return ArrayIterator.iterator(exports);
        }

        /**
         * Returns true of there are Exports on specified port of specified
         * PortInst.
         *
         * @param portId specified port
         * @return true if there are Exports on specified PortInst.
         */
        private boolean hasExportsOnPort(PortProtoId portId) {
            int chronIndex = portId.chronIndex;
            if (chronIndex < portsInfo.length) {
                Object portInfo = portsInfo[chronIndex];
                if (portInfo instanceof ImmutableExport) {
                    return true;
                } else if (portInfo instanceof PortConns) {
                    return ((PortConns) portInfo).hasExportsOnPort();
                }
            }
            return false;
        }

        /**
         * Method to return the number of Exports on specified PortInst.
         *
         * @param portId specified port
         * @return the number of Exports on specified PortInst.
         */
        private int getNumExportsOnPort(PortProtoId portId) {
            int chronIndex = portId.chronIndex;
            if (chronIndex < portsInfo.length) {
                Object portInfo = portsInfo[chronIndex];
                if (portInfo instanceof ImmutableExport) {
                    return 1;
                } else if (portInfo instanceof PortConns) {
                    return ((PortConns) portInfo).getNumExportsOnPort();
                }
            }
            return 0;
        }

        /**
         * Method to return an Iterator over all ImmutableExports on specified
         * PortInst.
         *
         * @param portId specified port
         * @return an Iterator over all ImmutableExports on specified PortInst.
         */
        private Iterator<ImmutableExport> getExportsOnPort(PortProtoId portId) {
            int chronIndex = portId.chronIndex;
            if (chronIndex < portsInfo.length) {
                Object portInfo = portsInfo[chronIndex];
                if (portInfo instanceof ImmutableExport) {
                    return ArrayIterator.singletonIterator((ImmutableExport) portInfo);
                } else if (portInfo instanceof PortConns) {
                    return ((PortConns) portInfo).getExportsOnPort();
                }
            }
            return ArrayIterator.emptyIterator();
        }

        private long getMemorySize(ObjSize objSize) {
            long s = objSize.sizeOf(this);
            assert portsInfo.length > 0;
            s += objSize.sizeOf(portsInfo);
            for (Object portInfo : portsInfo) {
                if (portInfo instanceof PortConns) {
                    s += ((PortConns) portInfo).getMemorySize(objSize);
                }
            }
            return s;
        }

        private void check(ImmutableNodeInst n) {
            for (int chronIndex = 0; chronIndex < portsInfo.length; chronIndex++) {
                Object portInfo = portsInfo[chronIndex];
                if (portInfo instanceof ImmutableArcInst) {
                    ImmutableArcInst a = (ImmutableArcInst) portInfo;
                    if (a.headNodeId == n.nodeId && a.headPortId.chronIndex == chronIndex) {
                        assert a.headNodeId == n.nodeId;
                        assert a.headPortId.parentId == n.protoId;
                        assert a.headPortId.chronIndex == chronIndex;
                        assert a.tailNodeId != n.nodeId || a.tailPortId.chronIndex != chronIndex;
                    } else {
                        assert a.tailNodeId == n.nodeId;
                        assert a.tailPortId.parentId == n.protoId;
                        assert a.tailPortId.chronIndex == chronIndex;
                        assert a.headNodeId != n.nodeId || a.headPortId.chronIndex != chronIndex;
                    }
                } else if (portInfo instanceof ImmutableExport) {
                    ImmutableExport e = (ImmutableExport) portInfo;
                    assert e.originalNodeId == n.nodeId;
                    assert e.originalPortId.parentId == n.protoId;
                    assert e.originalPortId.chronIndex == chronIndex;
                } else if (portInfo instanceof PortConns) {
                    PortConns pc = (PortConns) portInfo;
                    pc.check(n, n.protoId.getPortId(chronIndex));
                }
            }
        }
    }

    private static void checkArcs(ImmutableArcInst[] arcs, BitSet arcEnds, ImmutableNodeInst n, PortProtoId checkPortId) {
        if (checkPortId != null) {
            assert checkPortId.parentId == n.protoId;
        }
        for (int connIndex = 0; connIndex < arcs.length; connIndex++) {
            ImmutableArcInst a = arcs[connIndex];
            boolean end = arcEnds.get(connIndex);
            PortProtoId portId;
            if (end) {
                assert a.headNodeId == n.nodeId;
                portId = a.headPortId;
            } else {
                assert a.tailNodeId == n.nodeId;
                portId = a.tailPortId;
            }
            assert checkPortId != null ? portId == checkPortId : portId.parentId == n.protoId;
            if (connIndex > 0) {
                ImmutableArcInst oldA = arcs[connIndex - 1];
                boolean oldEnd = arcEnds.get(connIndex - 1);
                PortProtoId oldPortId = oldEnd ? oldA.headPortId : oldA.tailPortId;
                assert oldPortId.chronIndex <= portId.chronIndex;
                if (oldPortId.chronIndex == portId.chronIndex) {
                    assert oldA.arcId <= a.arcId;
                    if (oldA.arcId == a.arcId) {
                        assert !oldEnd && end;
                    }
                }
            }
        }
    }

    private static abstract class PortConns {

        List<ImmutableArcInst> getConnections(BitSet headEnds, int nodeId, PortProtoId portId) {
            List<ImmutableArcInst> list = getConnections();
            if (headEnds != null) {
                headEnds.clear();
                for (int i = 0; i < list.size(); i++) {
                    ImmutableArcInst a = list.get(i);
                    if (a.headNodeId == nodeId && a.headPortId == portId) {
                        assert a.headNodeId == nodeId;
                        assert a.headPortId == portId;
                        assert a.tailNodeId != nodeId || a.tailPortId != portId;
                        headEnds.set(i);
                    } else {
                        assert a.tailNodeId == nodeId;
                        assert a.tailPortId == portId;
                        assert a.headNodeId != nodeId || a.headPortId != portId;
                    }
                }
            }
            return list;
        }

        abstract List<ImmutableArcInst> getConnections();

        /**
         * Returns true of there are Connections on specified PortInst
         *
         * @return true if there are Connections on specified PortInst
         */
        abstract boolean hasConnections();

        /**
         * Method to return the number of Connections on specified PortInst.
         *
         * @return the number of Connections on specified ImmutableNodeInst.
         */
        abstract int getNumConnections();

        /**
         * Returns true of there are Exports on specified PortInst.
         *
         * @return true if there are Exports on specified PorteInst.
         */
        abstract boolean hasExportsOnPort();

        /**
         * Method to return the number of Exports on specified PortInst.
         *
         * @return the number of Exports on specified NodeInst.
         */
        abstract int getNumExportsOnPort();

        /**
         * Method to return an Iterator over all ImmutableExports on specified
         * NodeInst.
         *
         * @return an Iterator over all ImmutableExports on specified NodeInst.
         */
        abstract Iterator<ImmutableExport> getExportsOnPort();

        long getMemorySize(ObjSize objSize) {
            return objSize.sizeOf(this);
        }

        private void check(ImmutableNodeInst n, PortProtoId portId) {
            checkArcs(n, portId);
            checkExports(n, portId);
        }

        void checkArcs(ImmutableNodeInst n, PortProtoId portId) {
            int nodeId = n.nodeId;
            ImmutableArcInst oldA = null;
            for (ImmutableArcInst a : getConnections()) {
                if (a.headNodeId == nodeId && a.headPortId == portId) {
                    assert a.headNodeId == nodeId;
                    assert a.headPortId == portId;
                    assert a.tailNodeId != nodeId || a.tailPortId.chronIndex != portId.chronIndex;
                } else {
                    assert a.tailNodeId == nodeId;
                    assert a.tailPortId == portId;
                    assert a.headNodeId != nodeId || a.headPortId.chronIndex != portId.chronIndex;
                }
                if (oldA != null) {
                    assert oldA.arcId < a.arcId;
                }
                oldA = a;
            }
        }

        private void checkExports(ImmutableNodeInst n, PortProtoId portId) {
            ImmutableExport oldE = null;
            for (Iterator<ImmutableExport> eit = getExportsOnPort(); eit.hasNext();) {
                ImmutableExport e = eit.next();
                assert e.originalNodeId == n.nodeId;
                assert e.originalPortId == portId;
                if (oldE != null) {
                    assert oldE.exportId.chronIndex < e.exportId.chronIndex;
                }
                oldE = e;
            }
        }
    }

    private static class PortConA2E0 extends PortConns {

        private final ImmutableArcInst a0;
        private final ImmutableArcInst a1;

        PortConA2E0(ImmutableArcInst a0, ImmutableArcInst a1) {
            this.a0 = a0;
            this.a1 = a1;
        }

        @Override
        List<ImmutableArcInst> getConnections() {
            return ImmutableArrayList.of(a0, a1);
        }

        @Override
        boolean hasConnections() {
            return true;
        }

        @Override
        int getNumConnections() {
            return 2;
        }

        @Override
        boolean hasExportsOnPort() {
            return false;
        }

        @Override
        int getNumExportsOnPort() {
            return 0;
        }

        @Override
        Iterator<ImmutableExport> getExportsOnPort() {
            return ArrayIterator.emptyIterator();
        }
    }

    private static class PortConA3E0 extends PortConns {

        private final ImmutableArcInst a0;
        private final ImmutableArcInst a1;
        private final ImmutableArcInst a2;

        PortConA3E0(ImmutableArcInst a0, ImmutableArcInst a1, ImmutableArcInst a2) {
            this.a0 = a0;
            this.a1 = a1;
            this.a2 = a2;
        }

        @Override
        List<ImmutableArcInst> getConnections() {
            return ImmutableArrayList.of(a0, a1, a2);
        }

        @Override
        boolean hasConnections() {
            return true;
        }

        @Override
        int getNumConnections() {
            return 3;
        }

        @Override
        boolean hasExportsOnPort() {
            return false;
        }

        @Override
        int getNumExportsOnPort() {
            return 0;
        }

        @Override
        Iterator<ImmutableExport> getExportsOnPort() {
            return ArrayIterator.emptyIterator();
        }
    }

    private static class PortConANE0 extends PortConns {

        private final ImmutableArcInst[] arcs;

        PortConANE0(ImmutableArcInst[] arcs) {
            assert arcs.length > 0;
            this.arcs = arcs;
        }

        @Override
        List<ImmutableArcInst> getConnections() {
            return ImmutableArrayList.of(arcs);
        }

        @Override
        boolean hasConnections() {
            return true;
        }

        @Override
        int getNumConnections() {
            return arcs.length;
        }

        @Override
        boolean hasExportsOnPort() {
            return false;
        }

        @Override
        int getNumExportsOnPort() {
            return 0;
        }

        @Override
        Iterator<ImmutableExport> getExportsOnPort() {
            return ArrayIterator.emptyIterator();
        }

        @Override
        long getMemorySize(ObjSize objSize) {
            assert arcs.length > 0;
            return super.getMemorySize(objSize) + objSize.sizeOf(arcs);
        }
    }

    private static class PortConA1E1 extends PortConns {

        private final ImmutableArcInst a0;
        private final ImmutableExport e;

        private PortConA1E1(ImmutableArcInst a0, ImmutableExport e) {
            this.a0 = a0;
            this.e = e;
        }

        @Override
        List<ImmutableArcInst> getConnections() {
            return Collections.singletonList(a0);
        }

        @Override
        boolean hasConnections() {
            return true;
        }

        @Override
        int getNumConnections() {
            return 1;
        }

        @Override
        boolean hasExportsOnPort() {
            return true;
        }

        @Override
        int getNumExportsOnPort() {
            return 1;
        }

        @Override
        Iterator<ImmutableExport> getExportsOnPort() {
            return ArrayIterator.singletonIterator(e);
        }
    }

    private static class PortConA2E1 extends PortConns {

        private final ImmutableArcInst a0;
        private final ImmutableArcInst a1;
        private final ImmutableExport e;

        private PortConA2E1(ImmutableArcInst a0, ImmutableArcInst a1, ImmutableExport e) {
            this.a0 = a0;
            this.a1 = a1;
            this.e = e;
        }

        @Override
        List<ImmutableArcInst> getConnections() {
            return ImmutableArrayList.of(a0, a1);
        }

        @Override
        boolean hasConnections() {
            return true;
        }

        @Override
        int getNumConnections() {
            return 2;
        }

        @Override
        boolean hasExportsOnPort() {
            return true;
        }

        @Override
        int getNumExportsOnPort() {
            return 1;
        }

        @Override
        Iterator<ImmutableExport> getExportsOnPort() {
            return ArrayIterator.singletonIterator(e);
        }
    }

    private static class PortConA3E1 extends PortConns {

        private final ImmutableArcInst a0;
        private final ImmutableArcInst a1;
        private final ImmutableArcInst a2;
        private final ImmutableExport e;

        private PortConA3E1(ImmutableArcInst a0, ImmutableArcInst a1, ImmutableArcInst a2, ImmutableExport e) {
            this.a0 = a0;
            this.a1 = a1;
            this.a2 = a2;
            this.e = e;
        }

        @Override
        List<ImmutableArcInst> getConnections() {
            return ImmutableArrayList.of(a0, a1, a2);
        }

        @Override
        boolean hasConnections() {
            return true;
        }

        @Override
        int getNumConnections() {
            return 3;
        }

        @Override
        boolean hasExportsOnPort() {
            return true;
        }

        @Override
        int getNumExportsOnPort() {
            return 1;
        }

        @Override
        Iterator<ImmutableExport> getExportsOnPort() {
            return ArrayIterator.singletonIterator(e);
        }
    }

    private static class PortConANE1 extends PortConns {

        private final ImmutableArcInst[] arcs;
        private final ImmutableExport e;

        private PortConANE1(ImmutableArcInst[] arcs, ImmutableExport e) {
            assert arcs.length > 0;
            this.arcs = arcs;
            this.e = e;
        }

        @Override
        List<ImmutableArcInst> getConnections() {
            return ImmutableArrayList.of(arcs);
        }

        @Override
        boolean hasConnections() {
            return true;
        }

        @Override
        int getNumConnections() {
            return arcs.length;
        }

        @Override
        boolean hasExportsOnPort() {
            return true;
        }

        @Override
        int getNumExportsOnPort() {
            return 1;
        }

        @Override
        Iterator<ImmutableExport> getExportsOnPort() {
            return ArrayIterator.singletonIterator(e);
        }

        @Override
        long getMemorySize(ObjSize objSize) {
            assert arcs.length > 0;
            return super.getMemorySize(objSize) + objSize.sizeOf(arcs);
        }
    }

    private static class PortConANEN extends PortConns {

        private final ImmutableArcInst[] arcs;
        private final ImmutableExport[] exports;

        private PortConANEN(ImmutableArcInst[] arcs, ImmutableExport[] exports) {
            assert exports.length > 0;
            this.arcs = arcs;
            this.exports = exports;
        }

        @Override
        List<ImmutableArcInst> getConnections() {
            return ImmutableArrayList.of(arcs);
        }

        @Override
        boolean hasConnections() {
            return arcs.length > 0;
        }

        @Override
        int getNumConnections() {
            return arcs.length;
        }

        @Override
        boolean hasExportsOnPort() {
            return true;
        }

        @Override
        int getNumExportsOnPort() {
            return exports.length;
        }

        @Override
        Iterator<ImmutableExport> getExportsOnPort() {
            return ArrayIterator.iterator(exports);
        }

        @Override
        long getMemorySize(ObjSize objSize) {
            long s = super.getMemorySize(objSize);
            if (arcs != ImmutableArcInst.NULL_ARRAY) {
                s += objSize.sizeOf(arcs);
            }
            if (exports != ImmutableExport.NULL_ARRAY) {
                s += objSize.sizeOf(exports);
            }
            return s;
        }
    }

    private static class PortConALEN extends PortConns {

        private final ImmutableArcInst[] arcs;
        private final BitSet arcEnds;
        private final ImmutableExport[] exports;

        private PortConALEN(ImmutableArcInst[] arcs, BitSet arcEnds, ImmutableExport[] exports) {
            assert arcs.length > 0;
            this.arcs = arcs;
            this.arcEnds = arcEnds;
            this.exports = exports;
        }

        @Override
        List<ImmutableArcInst> getConnections(BitSet headEnds, int nodeId, PortProtoId portId) {
            if (headEnds != null) {
                headEnds.clear();
                headEnds.or(arcEnds);
            }
            return getConnections();
        }

        @Override
        List<ImmutableArcInst> getConnections() {
            return ImmutableArrayList.of(arcs);
        }

        @Override
        boolean hasConnections() {
            return true;
        }

        @Override
        int getNumConnections() {
            return arcs.length;
        }

        @Override
        boolean hasExportsOnPort() {
            return exports.length != 0;
        }

        @Override
        int getNumExportsOnPort() {
            return exports.length;
        }

        @Override
        Iterator<ImmutableExport> getExportsOnPort() {
            return ArrayIterator.iterator(exports);
        }

        @Override
        long getMemorySize(ObjSize objSize) {
            long s = super.getMemorySize(objSize);
            if (arcs != ImmutableArcInst.NULL_ARRAY) {
                s += objSize.sizeOf(arcs);
            }
            s += sizeOfBitSet(objSize, arcEnds);
            if (exports != ImmutableExport.NULL_ARRAY) {
                s += objSize.sizeOf(exports);
            }
            return s;
        }

        @Override
        void checkArcs(ImmutableNodeInst n, PortProtoId portId) {
            CellRevisionConn.checkArcs(arcs, arcEnds, n, portId);
        }
    }
}
