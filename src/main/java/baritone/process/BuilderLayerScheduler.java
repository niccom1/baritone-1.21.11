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
     * Steps to the next band in the configured order, skipping nothing by itself.
     *
     * @return {@code false} when there is no further band to work on
     */
    public boolean advance() {
        int next = descending ? sliceIndex - 1 : sliceIndex + 1;
        if (next < firstSliceIndex || next > lastSliceIndex) {
            return false;
        }
        sliceIndex = next;
        return true;
    }

    /**
     * Puts the cursor back on the first band, for when the whole target is being built again.
     */
    public void reset() {
        sliceIndex = descending ? lastSliceIndex : firstSliceIndex;
    }

    @Override
    public String toString() {
        return String.format("layer %s/%s (y %s-%s)", sliceIndex + 1, sliceCount(), sliceMinY(), sliceMaxY());
    }
}
