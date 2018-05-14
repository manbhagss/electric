/* -*- tab-width: 4 -*-
 *
 * Electric(tm) VLSI Design System
 *
 * File: PublicDebugMenu.java
 *
 * Copyright (c) 2011, Static Free Software. All rights reserved.
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
package com.sun.electric.tool.user.menus;

import com.sun.electric.database.EditingPreferences;
import com.sun.electric.database.Snapshot;
import static com.sun.electric.tool.user.menus.EMenuItem.SEPARATOR;

import com.sun.electric.database.hierarchy.Cell;
import com.sun.electric.database.hierarchy.EDatabase;
import com.sun.electric.database.id.CellId;
import com.sun.electric.tool.Job;
import com.sun.electric.tool.JobException;
import com.sun.electric.tool.drc.MinArea;
import com.sun.electric.tool.io.input.JELIB2;
import com.sun.electric.tool.routing.Routing;
import com.sun.electric.tool.routing.SeaOfGates;
import com.sun.electric.tool.routing.seaOfGates.SeaOfGatesEngine;
import com.sun.electric.tool.routing.seaOfGates.SeaOfGatesEngineFactory;
import com.sun.electric.tool.routing.seaOfGates.SeaOfGatesHandlers;
import com.sun.electric.tool.user.UserInterfaceMain;
import java.io.PrintStream;

/**
 * 
 * @author Felix Schmdit
 * 
 */
public class PublicDebugMenu {

	static EMenuItem makeMenu() {
		return new EMenu("Debug",

		// SEPARATOR,
				new EMenu("DRC",

				new EMenuItem("Check _Minimum Area...") {
					public void run() {
						MinArea.checkMinareaLay();
					}
				}, new EMenuItem("Import Minimum Area _Test...") {
					public void run() {
						MinArea.readMinareaLay();
					}
				}, new EMenuItem("E_xport Minimum Area Test...") {
					public void run() {
						MinArea.writeMinareaLay();
					}
				}),
                
                new EMenu("Fast JELIB reader",

                jelibItem("Database", true, true, true, true, true),
                jelibItem("Snapshot", true, true, true, true, false),
                jelibItem("primitiveBounds", true, true, true, false, false),
                jelibItem("doBackup", true, true, false, false, false),
                jelibItem("instantiate", true, false, false, false, false),
                jelibItem("only parse", false, false, false, false, false)),
                
                new EMenu("SeaOfGatesRouter",

                sogItem("Animation", SeaOfGatesHandlers.Save.SAVE_SNAPSHOTS),
                sogItem("Partial-Animation", SeaOfGatesHandlers.Save.SAVE_PERIODIC),
                sogItem("Once", SeaOfGatesHandlers.Save.SAVE_ONCE),
                SEPARATOR,
                sogItem("Dummy on CHANGE", Job.Type.CHANGE),
                sogItem("Dummy on SERVER_EXAMINE", Job.Type.SERVER_EXAMINE),
                sogItem("Dummy on CLIENT_EXAMINE", Job.Type.CLIENT_EXAMINE),
                new EMenuItem("Dummy on client") {
                    @Override
                    public void run() {
                        Cell cell = Job.getUserInterface().needCurrentCell();
                        if (cell == null) return;
                        routeIt(cell, UserInterfaceMain.getEditingPreferences(), System.out);
                    }
                },
                new EMenuItem("Dummy in Thread") {
                    @Override
                    public void run() {
                        Cell cell = Job.getUserInterface().needCurrentCell();
                        if (cell == null) return;
                        new SeaOfGatesThread(cell, UserInterfaceMain.getEditingPreferences()).start();
                    }
                }));
	}
    
    // Dima's menu items
    
    private static EMenuItem jelibItem(String text,
            final boolean instantiate,
            final boolean doBackup,
            final boolean getPrimitiveBounds,
            final boolean doSnapshot,
            final boolean doDatabase) {
       return new EMenuItem(text) {

            @Override
            public void run() {
                JELIB2.newJelibReader(instantiate, doBackup, getPrimitiveBounds, doSnapshot, doDatabase);
            }
        };
    }
    
    private static EMenuItem sogItem(String text, final SeaOfGatesHandlers.Save save) {
        return new EMenuItem(text) {

            @Override
            public void run() {
                Cell cell = Job.getUserInterface().needCurrentCell();
                if (cell != null) {
                    SeaOfGatesHandlers.startInJob(cell, null, SeaOfGatesEngineFactory.SeaOfGatesEngineType.defaultVersion, save);
                }
            }
        };
    }
    
    private static EMenuItem sogItem(String text, final Job.Type jobType) {
        return new EMenuItem(text) {

            @Override
            public void run() {
                Cell cell = Job.getUserInterface().needCurrentCell();
                if (cell != null) {
                    new SeaOfGatesJob(cell, jobType).startJob();
                }
            }
        };
    }
    
    /**
     * Class to run sea-of-gates routing in a separate Job.
     */
    private static class SeaOfGatesJob extends Job {

        private final Cell cell;

        protected SeaOfGatesJob(Cell cell, Job.Type jobType) {
            super("Sea-Of-Gates Route", Routing.getRoutingTool(), jobType, null, null, Job.Priority.USER);
            this.cell = cell;
        }

        @Override
        public boolean doIt() throws JobException {
            routeIt(cell, getEditingPreferences(), System.out);
            return true;
        }
    }

    private static class SeaOfGatesThread extends Thread {
        private final Snapshot snapshot;
        private final CellId cellId;
        private final EditingPreferences ep;
        
        private SeaOfGatesThread(Cell cell, EditingPreferences ep) {
            snapshot = cell.getDatabase().backup();
            cellId = cell.getId();
            this.ep = ep;
        }
        
        @Override
        public void run() {
            EDatabase database = new EDatabase(snapshot, "dummy");
            Cell cell = database.getCell(cellId);
            routeIt(cell, ep, System.err);
        }
    }
     
    private static void routeIt(Cell cell, EditingPreferences ep, PrintStream out) {
        SeaOfGatesEngine router = SeaOfGatesEngineFactory.createSeaOfGatesEngine();
        SeaOfGates.SeaOfGatesOptions prefs = new SeaOfGates.SeaOfGatesOptions();
        prefs.useParallelRoutes = true;
        router.setPrefs(prefs);
        SeaOfGatesEngine.Handler handler = SeaOfGatesHandlers.getDummy(ep, out);
        router.routeIt(handler, cell, false);
    }
}
