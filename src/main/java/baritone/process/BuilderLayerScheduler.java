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
 *
 * ---------------------------------------------------------------------------
 * Layered build modification (not part of upstream Baritone)
 * Upstream project: https://github.com/cabaletta/baritone
 * This file is new in the layered build fork; it is released under the same
 * LGPL-3.0 terms as the rest of Baritone.
 * ---------------------------------------------------------------------------
 */

package baritone.process;

import java.util.TreeSet;

/**
 * Splits a build target into horizontal bands of Y levels and hands them out one at a time.
 * <p>
 * The builder used to treat every remaining block of the whole schematic as a candidate at once, so it regularly
 * travelled between distant corners and changed Y level on the way. Working through one band until it is finished
 * keeps the builder inside a single horizontal plane, and turns the change between two bands into one deliberate
 * vertical move instead of the many accidental ones.
 * <p>
 * Ordering is decided by the caller: removing blocks ({@code descending == true}) is done from the top down so the
 * builder never has to dig itself a shaft and fall into the hole it just made, placing blocks from the bottom up so
 * every placed block has support underneath it.
 */
public final class BuilderLayerScheduler {

    private final int heightY;
    private final int sliceHeight;
    private final boolean descending;
    private final int firstSliceIndex;
    private final int lastSliceIndex;

    /**
     * Index of the band that is currently being worked on.
     */
    private int sliceIndex;

    /**
     * Bands that had nothing the builder could do the last time they were looked at.
     * <p>
     * They are not finished. They are only waiting for something the builder does not have at the moment, which is
     * almost always a material, and they get another turn once {@link #clearDeferred()} is called. Keeping them here is
     * what lets a long build keep going when one band runs into a missing block instead of stopping the whole job.
     */
    private final TreeSet<Integer> deferred = new TreeSet<>();

    /**
     * @param heightY     total height of the build target, in Y levels
     * @param sliceHeight how many Y levels one band contains, at least 1
     * @param descending  {@code true} to work from the highest band down, {@code false} from the lowest band up
     * @param startAtY    lowest relative Y that still needs work, used to skip layers that are known to be finished
     * @param stopAtY     relative Y where the build target ends, exclusive
     */
    public BuilderLayerScheduler(int heightY, int sliceHeight, boolean descending, int startAtY, int stopAtY) {
        this.heightY = Math.max(1, heightY);
        this.sliceHeight = Math.max(1, sliceHeight);
        this.descending = descending;
        int sliceCount = (this.heightY + this.sliceHeight - 1) / this.sliceHeight;
        int first = Math.floorDiv(clamp(startAtY, 0, this.heightY - 1), this.sliceHeight);
        // stopAtY is exclusive, so the last layer that still needs work is the one holding stopAtY - 1
        int last = Math.floorDiv(clamp(stopAtY - 1, 0, this.heightY - 1), this.sliceHeight);
        this.firstSliceIndex = Math.min(first, sliceCount - 1);
        this.lastSliceIndex = Math.max(this.firstSliceIndex, Math.min(last, sliceCount - 1));
        this.sliceIndex = descending ? this.lastSliceIndex : this.firstSliceIndex;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public boolean isDescending() {
        return descending;
    }

    /**
     * @return the index of the band that is currently being worked on, 0 based
     */
    public int sliceIndex() {
        return sliceIndex;
    }

    /**
     * @return how many bands the build target was split into
     */
    public int sliceCount() {
        return (heightY + sliceHeight - 1) / sliceHeight;
    }

    public int sliceMinY() {
        return sliceIndex * sliceHeight;
    }

    public int sliceMaxY() {
        return Math.min(heightY - 1, sliceMinY() + sliceHeight - 1);
    }

    /**
     * @param relativeY a Y level relative to the schematic origin
     * @return whether that Y level belongs to the band that is currently being worked on
     */
    public boolean containsLayer(int relativeY) {
        return relativeY >= sliceMinY() && relativeY <= sliceMaxY();
    }

    /**
     * Steps to the next band in the configured order.
     * <p>
     * When the ordered sweep runs out, bands that were set aside earlier get their turn instead of the build being
     * declared finished: a band that was set aside was waiting for a neighbour or for materials, and by the time the
     * sweep reaches the end those neighbours usually exist. Without this pass the set-aside bands were only retried on
     * {@code resume}, which is what made a layered build look like it had skipped a layer and then gone back to it much
     * later, or never.
     *
     * @return {@code false} when there is no further band to work on
     */
    public boolean advance() {
        // this band is finished, so it does not have to be retried later
        deferred.remove(sliceIndex);
        int next = descending ? sliceIndex - 1 : sliceIndex + 1;
        if (next >= firstSliceIndex && next <= lastSliceIndex) {
            sliceIndex = next;
            return true;
        }
        if (retryDeferred()) {
            return true;
        }
        return false;
    }

    /**
     * Moves the cursor to the nearest band that was set aside, in the configured direction.
     *
     * @return whether such a band was found
     */
    private boolean retryDeferred() {
        while (!deferred.isEmpty()) {
            // descending works from the top down, so the highest set-aside band is the nearest one to come back to
            int candidate = descending ? deferred.last() : deferred.first();
            deferred.remove(candidate);
            sliceIndex = candidate;
            return true;
        }
        return false;
    }

    /**
     * Sets the current band aside because nothing in it can be built right now, and moves the cursor on to the next
     * band that has not been set aside yet.
     * <p>
     * A band that is set aside is not finished, it is only waiting for materials, so pausing the whole build over it
     * would stall every other band as well. That is exactly what used to happen to long jobs: one band ran into a block
     * the player did not have at hand and the build stopped dead with "Unable to do it".
     *
     * @return {@code false} when no band is left to work on, i.e. every band has been set aside already
     */
    public boolean deferSlice() {
        deferred.add(sliceIndex);
        int total = lastSliceIndex - firstSliceIndex + 1;
        if (deferred.size() >= total) {
            return false; // every band is waiting for something, so there is nothing left to try
        }
        int next = sliceIndex;
        for (int i = 0; i < total; i++) {
            next = descending ? next - 1 : next + 1;
            if (next < firstSliceIndex || next > lastSliceIndex) {
                // wrap around so that a band behind the cursor gets its turn before the build gives up
                next = descending ? lastSliceIndex : firstSliceIndex;
            }
            if (!deferred.contains(next)) {
                sliceIndex = next;
                return true;
            }
        }
        return false;
    }

    /**
     * Gives every band that was set aside another turn, for when the player changed something that could unblock them.
     */
    public void clearDeferred() {
        deferred.clear();
    }

    /**
     * @return how many bands are currently set aside waiting to be unblocked
     */
    public int deferredCount() {
        return deferred.size();
    }

    /**
     * Puts the cursor back on the first band, for when the whole target is being built again.
     */
    public void reset() {
        sliceIndex = descending ? lastSliceIndex : firstSliceIndex;
        deferred.clear();
    }

    @Override
    public String toString() {
        return String.format("layer %s/%s (y %s-%s)", sliceIndex + 1, sliceCount(), sliceMinY(), sliceMaxY());
    }
}
