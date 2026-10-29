/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The blocks the pathfinder placed for itself while walking, so the builder leaves them alone.
 * <p>
 * To cross a gap or climb a wall Baritone places throwaway blocks under its own feet. Those blocks are not part of any
 * schematic, so the builder sees "a block that should not be there" and breaks it - and then the path immediately needs
 * it again to reach the position where it wanted to build. That is a loop with no end: place support, break support,
 * place support. Recording every position the path placed makes that block recognisable, so the builder can simply not
 * break it while the path still depends on it.
 * <p>
 * Entries expire on their own, because a block that was scaffolding ten minutes ago is just a normal block now and the
 * builder is allowed to clean it up eventually.
 */
public final class PlacedBlocks {

    /**
     * How long a path placement stays off limits for the builder. Long enough to survive a detour, short enough that the
     * leftover scaffolding of an old route does not block the build forever.
     */
    private static final long LIFETIME_MS = 90_000L;

    private static final Map<BlockPos, Long> PLACED = new HashMap<>();

    private PlacedBlocks() {}

    public static synchronized void remember(BlockPos pos) {
        if (pos == null) {
            return;
        }
        long now = System.currentTimeMillis();
        PLACED.put(pos.immutable(), now);
        if (PLACED.size() > 512) {
            prune(now);
        }
    }

    public static synchronized boolean isOurs(BlockPos pos) {
        if (pos == null || PLACED.isEmpty()) {
            return false;
        }
        Long when = PLACED.get(pos);
        if (when == null) {
            return false;
        }
        if (System.currentTimeMillis() - when > LIFETIME_MS) {
            PLACED.remove(pos);
            return false;
        }
        return true;
    }

    public static synchronized void forget(BlockPos pos) {
        if (pos != null) {
            PLACED.remove(pos);
        }
    }

    public static synchronized void clear() {
        PLACED.clear();
    }

    public static synchronized int size() {
        return PLACED.size();
    }

    private static void prune(long now) {
        Iterator<Map.Entry<BlockPos, Long>> it = PLACED.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > LIFETIME_MS) {
                it.remove();
            }
        }
    }
}
