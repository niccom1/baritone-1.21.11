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
 * Changed here: the build target is worked through one horizontal Y layer at
 * a time (BuilderLayerScheduler + LayerSliceSchematic), see README.md.
 * ---------------------------------------------------------------------------
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.*;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.utils.*;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneLog;
import baritone.utils.PlacedBlocks;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.PathingCommandContext;
import baritone.utils.schematic.MapArtSchematic;
import baritone.utils.schematic.SchematicSystem;
import baritone.utils.schematic.SelectionSchematic;
import baritone.utils.schematic.litematica.LitematicaHelper;
import baritone.utils.schematic.schematica.SchematicaHelper;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Tuple;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public final class BuilderProcess extends BaritoneProcessHelper implements IBuilderProcess {

    /**
     * Property names that describe how a block is oriented instead of what it is made of, for the properties whose
     * values are plain enums and whose type therefore says nothing about them.
     */
    private static final Set<String> ORIENTATION_PROPERTY_NAMES = ImmutableSet.of(
            "facing", "axis", "rotation", "orientation", "half", "shape", "type", "open", "attachment", "face",
            "hinge", "north", "east", "south", "west", "up", "down", "tilt", "wall", "side", "vertical_direction"
    );

    /** Horizontal radius of the local placement / breaking scan around the builder. */
    private static final int LOCAL_SCAN_RADIUS = 5;
    /**
     * How far a wrongly-placed block may be before the builder leaves it alone until it is next to it.
     * <p>
     * The break scan used to reuse the placement radius, so every block within five blocks that did not match the plan
     * was mined on the way past, even with nothing on the route: the player saw the builder stop in the middle of
     * walking, chew through a few blocks it did not need to touch, and carry on. Two blocks is "the thing right in
     * front of me", which is the only block worth interrupting a placement for.
     */
    private static final int BREAK_SCAN_RADIUS = 2;
    /**
     * Failed placement attempts at one position that are tolerated before the builder stops walking back to it.
     * <p>
     * High enough that a spot which merely has to be dug open first is not given up on, low enough that a spot no
     * reachable side can ever satisfy cannot hold a build hostage for minutes on end.
     */
    private static final int PLACEMENT_STRUGGLE_LIMIT = 1200;
    /** How long the whole-band fallback scan stays quiet after it ran, in ticks. */
    private static final int WHOLE_BAND_RESCAN_TICKS = 10;
    /**
     * Ticks the builder waits for the click to land on the wanted face before leaving that target for later.
     * <p>
     * The click is only sent when the aimed face would really produce the wanted state, so this is not a retry budget
     * for wrong states: it is the time the camera and the movement packets need to reach the face. Three seconds is
     * generous for that, and short enough that one unreachable face cannot hold the whole band.
     */
    private static final int AIM_GIVE_UP_TICKS = 60;
    /**
     * Mine-and-replace rounds on one position before its state is accepted as it is.
     * <p>
     * Reachable when a block's state is decided by the world rather than by the click: putting it back reproduces the
     * same state, so the seventh round would do exactly what the first six did. Six is past the point where a genuine
     * placement mistake would still be plausible.
     */
    private static final int REPAIR_ROUND_LIMIT = 6;
    /** How often the progress line is refreshed while the build is running, in ticks. */
    private static final int STATUS_INTERVAL_TICKS = 40;
    /**
     * How many path calculations in a row may fail for the same goal before the builder gives up on the blocks it was
     * asking about and looks at the rest of the band instead.
     * <p>
     * This is the "it just stands there and does nothing until I move" bug: the builder hands the same unreachable
     * {@link GoalAdjacent} to the pathfinder every tick, the pathfinder fails every time, {@code calcFailed} arrives and
     * nobody acts on it, so the build silently idles. A few seconds of that is enough to conclude the target is not
     * walkable from here.
     */
    private static final int CALC_FAIL_STREAK_LIMIT = 60;
    /** How many ticks the builder may sit with a goal set while no path is being followed, before treating it as stuck. */
    private static final int IDLE_STALL_LIMIT = 200;
    /** How many ticks a path may be followed without the player actually moving before the segment is cancelled. */
    private static final int STILL_TICK_LIMIT = 200;
    /**
     * How many ticks may pass with the goal already satisfied by the player's feet while nothing is being clicked.
     * That state can never resolve on its own, because a satisfied goal never starts a path, so the fuse is short.
     */
    private static final int SATISFIED_STALL_LIMIT = 40;
    /** Offsets of each square ring, indexed by radius, so the closest candidates get tried first. */
    private static final int[][][] LOCAL_SCAN_RINGS = buildLocalScanRings();
    /** Vertical offsets ordered by distance from the builder's feet, for the placement scan. */
    private static final int[] VERTICAL_ORDER_PLACE = verticalOffsetOrder(-LOCAL_SCAN_RADIUS, 1);
    /** Same, for the breaking scan while digging from above is allowed. */
    private static final int[] VERTICAL_ORDER_BREAK_FROM_ABOVE = verticalOffsetOrder(-1, BREAK_SCAN_RADIUS);
    /** Same, for the breaking scan when digging from above is not allowed. */
    private static final int[] VERTICAL_ORDER_BREAK = verticalOffsetOrder(0, BREAK_SCAN_RADIUS);

    private HashSet<BetterBlockPos> incorrectPositions;
    private LongOpenHashSet observedCompleted; // positions that are completed even if they're out of render distance and we can't make sure right now
    private String name;
    private ISchematic realSchematic;
    private ISchematic schematic;
    private Vec3i origin;
    private int ticks;
    private boolean paused;
    private int layer;
    private BuilderLayerScheduler layerScheduler;
    private int numRepeats;
    private List<BlockState> approxPlaceable;
    private List<Item> approxPlaceableSnapshot; // inventory contents the list above was built from
    private int wholeBandCooldown; // ticks left before the whole-layer fallback scan may run again
    /** Placement sides already known to produce a given desired state, so the simulation runs once per state. */
    private final Map<BlockState, Set<Direction>> fittingPlacementSides = new HashMap<>();
    /** Positions that stay reachable but never become placeable, and how many attempts they have eaten so far. */
    private final Map<BetterBlockPos, Integer> placementStruggles = new HashMap<>();
    /** Positions given up on until the next resume, so one impossible block cannot stall a whole band. */
    private final Set<BetterBlockPos> abandonedPlacements = new HashSet<>();
    /** Path calculations in a row that failed while the builder was asking for the goal below. */
    private int calcFailStreak;
    /** Ticks spent with a goal set but no path being followed, which is what "standing still doing nothing" looks like. */
    private int idleTicks;
    /** How many times a stall was broken since the build started, for the log. */
    private int stallRecoveries;
    /** The goal handed out on the previous tick, and the positions it was built from. */
    private Goal lastGoalSent;
    private final Set<BetterBlockPos> lastGoalTargets = new LinkedHashSet<>();
    /** Positions whose orientation problem has already been reported, so the log says it once instead of every tick. */
    private final Set<BlockPos> placementWarnings = new HashSet<>();
    /** Last feet position that counted as progress, and how many ticks have passed without the player moving. */
    private BetterBlockPos lastProgressFeet;
    private int stillTicks;
    /** Counts ticks so the per-tick log line can be throttled instead of filling the file. */
    private int tickLogCounter;
    /**
     * The last plan that was written to the log, so the file records decisions instead of every tick.
     */
    private String lastPlannedSignature;
    /** The last mining target written to the log, and the positions already reported as placed. */
    private BetterBlockPos lastBreakLogged;
    private final Set<BlockPos> loggedPlacements = new HashSet<>();
    /** The last assemble summary written to the log, so unchanged counts do not repeat. */
    private String lastAssembleSummary;
    /**
     * Properties whose value a placement cannot decide, learned per block.
     * <p>
     * Some states are not chosen by how the block is placed at all, they come out of the world afterwards: a rail
     * picks its shape from its neighbours, and re-placing it in the same spot reproduces the same shape. Comparing the
     * whole state then reports "wrong" forever, and the builder mines the block and puts it back. Once a difference is
     * observed right after our own click, that property is written off for that block, and later comparisons of that
     * block ignore it. Nothing is hardcoded per block type, the set stays empty until the world proves otherwise.
     */
    private final Map<Block, Set<Property<?>>> derivedProperties = new HashMap<>();
    /** The block we last clicked for, so the next tick can compare what actually came out. */
    private BetterBlockPos pendingPlacementPos;
    private BlockState pendingPlacementWanted;
    /** Positions where mine-and-replace has been tried and changed nothing, so it is not worth another round. */
    private final Map<BlockPos, Integer> repairRounds = new HashMap<>();
    /** Positions whose state is accepted as it is, because replacing them provably changes nothing. */
    private final Set<BetterBlockPos> stateTolerated = new HashSet<>();
    /** Ticks spent waiting for the click to land on the wanted face, per target position. */
    private final Map<BetterBlockPos, Integer> aimTicks = new HashMap<>();
    /** Targets given up on for the moment after the aim never landed, so the builder does something else. */
    private final Set<BetterBlockPos> aimGiveUp = new HashSet<>();
    /** Progress line bookkeeping: the hint lives and dies with the build instead of being printed once and left. */
    private String lastStatusLine;
    private int statusCountdown;
    public int stopAtHeight = 0;

    public BuilderProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void build(String name, ISchematic schematic, Vec3i origin) {
        this.name = name;
        this.schematic = schematic;
        this.realSchematic = null;
        boolean buildingSelectionSchematic = schematic instanceof SelectionSchematic;
        if (!Baritone.settings().buildSubstitutes.value.isEmpty()) {
            this.schematic = new SubstituteSchematic(this.schematic, Baritone.settings().buildSubstitutes.value);
        }
        if (Baritone.settings().buildSchematicMirror.value != net.minecraft.world.level.block.Mirror.NONE) {
            this.schematic = new MirroredSchematic(this.schematic, Baritone.settings().buildSchematicMirror.value);
        }
        if (Baritone.settings().buildSchematicRotation.value != net.minecraft.world.level.block.Rotation.NONE) {
            this.schematic = new RotatedSchematic(this.schematic, Baritone.settings().buildSchematicRotation.value);
        }
        // TODO this preserves the old behavior, but maybe we should bake the setting value right here
        this.schematic = new MaskSchematic(this.schematic) {
            @Override
            public boolean partOfMask(int x, int y, int z, BlockState current) {
                // partOfMask is only called inside the schematic so desiredState is not null
                return !Baritone.settings().buildSkipBlocks.value.contains(this.desiredState(x, y, z, current, Collections.emptyList()).getBlock());
            }
        };
        int x = origin.getX();
        int y = origin.getY();
        int z = origin.getZ();
        if (Baritone.settings().schematicOrientationX.value) {
            x += schematic.widthX();
        }
        if (Baritone.settings().schematicOrientationY.value) {
            y += schematic.heightY();
        }
        if (Baritone.settings().schematicOrientationZ.value) {
            z += schematic.lengthZ();
        }
        this.origin = new Vec3i(x, y, z);
        this.paused = false;
        this.layer = Baritone.settings().startAtLayer.value;
        this.stopAtHeight = schematic.heightY();
        if (Baritone.settings().buildOnlySelection.value && buildingSelectionSchematic) {  // currently redundant but safer maybe
            if (baritone.getSelectionManager().getSelections().length == 0) {
                logDirect("Poor little kitten forgot to set a selection while BuildOnlySelection is true");
                this.stopAtHeight = 0;
            } else if (Baritone.settings().buildInLayers.value) {
                OptionalInt minim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.min().y).min();
                OptionalInt maxim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.max().y).max();
                if (minim.isPresent() && maxim.isPresent()) {
                    int startAtHeight = Baritone.settings().layerOrder.value ? y + schematic.heightY() - maxim.getAsInt() : minim.getAsInt() - y;
                    this.stopAtHeight = (Baritone.settings().layerOrder.value ? y + schematic.heightY() - minim.getAsInt() : maxim.getAsInt() - y) + 1;
                    // layerHeight is a user setting and can be zero, which used to divide this straight into a crash
                    this.layer = Math.max(this.layer, startAtHeight / Math.max(1, Baritone.settings().layerHeight.value));  // startAtLayer or startAtHeight, whichever is highest
                    logDebug(String.format("Schematic starts at y=%s with height %s", y, schematic.heightY()));
                    logDebug(String.format("Selection starts at y=%s and ends at y=%s", minim.getAsInt(), maxim.getAsInt()));
                    logDebug(String.format("Considering relevant height %s - %s", startAtHeight, this.stopAtHeight));
                }
            }
        }

        this.numRepeats = 0;
        this.observedCompleted = new LongOpenHashSet();
        this.incorrectPositions = null;
        this.layerScheduler = null; // the layer cursor belongs to the schematic that was just replaced
        this.placementStruggles.clear();
        this.abandonedPlacements.clear();
        this.fittingPlacementSides.clear();
        this.placementWarnings.clear();
        this.calcFailStreak = 0;
        this.idleTicks = 0;
        this.stallRecoveries = 0;
        this.lastGoalSent = null;
        this.lastGoalTargets.clear();
        this.resetPlacementTracking();
    }

    public void resume() {
        BaritoneLog.log("BUILD", "resume() at %s, dropped %s abandoned positions and the trimmed pending set; retrying everything",
                feetForLog(), abandonedPlacements.size());
        paused = false;
        // The pending set is trimmed to the blocks around the player every tick, and a pause usually leaves it reduced
        // to the few blocks that could not be finished. Reusing that set would pause again on the very next tick, so
        // drop it and let the next tick look at the whole target again. Bands that were set aside because they were
        // missing materials get another turn as well: the player probably went and got them.
        incorrectPositions = null;
        wholeBandCooldown = 0;
        fittingPlacementSides.clear(); // the surroundings changed while paused, so cached sides may no longer hold
        placementStruggles.clear();
        abandonedPlacements.clear(); // the builder may stand somewhere else now, so blocked spots deserve a retry
        placementWarnings.clear();
        calcFailStreak = 0;
        idleTicks = 0;
        stillTicks = 0;
        lastProgressFeet = null;
        lastGoalSent = null;
        lastGoalTargets.clear();
        resetPlacementTracking();
        if (layerScheduler != null) {
            layerScheduler.clearDeferred();
        }
    }

    public void pause() {
        BaritoneLog.log("BUILD", "pause() requested externally at %s (pending=%s, layer=%s)",
                feetForLog(), pendingCount(), layerScheduler == null ? "<none>" : layerScheduler.toString());
        paused = true;
    }

    /**
     * {@code resume()} and {@code pause()} can be reached from commands that run while no world is loaded, where
     * {@code playerFeet()} throws. Logging must never be the reason the client dies, so the log gets this instead.
     */
    private Object feetForLog() {
        return ctx.player() == null ? "<no player>" : ctx.playerFeet();
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public boolean build(String name, File schematic, Vec3i origin) {
        Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(schematic);
        if (!format.isPresent()) {
            return false;
        }
        IStaticSchematic parsed;
        try {
            parsed = format.get().parse(new FileInputStream(schematic));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        ISchematic schem = applyMapArtAndSelection(origin, parsed);
        build(name, schem, origin);
        return true;
    }

    private ISchematic applyMapArtAndSelection(Vec3i origin, IStaticSchematic parsed) {
        ISchematic schematic = parsed;
        if (Baritone.settings().mapArtMode.value) {
            schematic = new MapArtSchematic(parsed);
        }
        if (Baritone.settings().buildOnlySelection.value) {
            schematic = new SelectionSchematic(schematic, origin, baritone.getSelectionManager().getSelections());
        }
        return schematic;
    }

    @Override
    public void buildOpenSchematic() {
        if (SchematicaHelper.isSchematicaPresent()) {
            Optional<Tuple<IStaticSchematic, BlockPos>> schematic = SchematicaHelper.getOpenSchematic();
            if (schematic.isPresent()) {
                IStaticSchematic raw = schematic.get().getA();
                BlockPos origin = schematic.get().getB();
                ISchematic schem = applyMapArtAndSelection(origin, raw);
                this.build(raw.toString(), schem, origin);
            } else {
                logDirect("No schematic currently open");
            }
        } else {
            logDirect("Schematica is not present");
        }
    }

    @Override
    public void buildOpenLitematic(int i) {
        if (LitematicaHelper.isLitematicaPresent()) {
            //if java.lang.NoSuchMethodError is thrown see comment in SchematicPlacementManager
            if (LitematicaHelper.hasLoadedSchematic(i)) {
                Tuple<IStaticSchematic, Vec3i> schematic = LitematicaHelper.getSchematic(i);
                Vec3i correctedOrigin = schematic.getB();
                ISchematic schematic2 = applyMapArtAndSelection(correctedOrigin, schematic.getA());
                build(schematic.getA().toString(), schematic2, correctedOrigin);
            } else {
                logDirect(String.format("List of placements has no entry %s", i + 1));
            }
        } else {
            logDirect("Litematica is not present");
        }
    }

    public void clearArea(BlockPos corner1, BlockPos corner2) {
        BlockPos origin = new BlockPos(Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()));
        int widthX = Math.abs(corner1.getX() - corner2.getX()) + 1;
        int heightY = Math.abs(corner1.getY() - corner2.getY()) + 1;
        int lengthZ = Math.abs(corner1.getZ() - corner2.getZ()) + 1;
        build("clear area", new FillSchematic(widthX, heightY, lengthZ, Blocks.AIR.defaultBlockState()), origin);
    }

    @Override
    public List<BlockState> getApproxPlaceable() {
        return new ArrayList<>(approxPlaceable);
    }

    @Override
    public boolean isActive() {
        return schematic != null;
    }

    public BlockState placeAt(int x, int y, int z, BlockState current) {
        if (!isActive()) {
            return null;
        }
        if (!schematic.inSchematic(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current)) {
            return null;
        }
        BlockState state = schematic.desiredState(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current, this.approxPlaceable);
        if (state.getBlock() instanceof AirBlock) {
            return null;
        }
        return state;
    }

    private Optional<Tuple<BetterBlockPos, Rotation>> toBreakNearPlayer(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        BetterBlockPos pathStart = baritone.getPathingBehavior().pathStart();
        int[] verticalOrder = Baritone.settings().breakFromAbove.value ? VERTICAL_ORDER_BREAK_FROM_ABOVE : VERTICAL_ORDER_BREAK;
        // Closest ring first. The old fixed dx/dy/dz order started in the far negative corner of the 11x7x11 box, so
        // the block the builder was standing next to could lose to one on the other side of the box and it would walk
        // away from the work it was already beside.
        for (int radius = 0; radius <= BREAK_SCAN_RADIUS; radius++) {
            for (int dy : verticalOrder) {
                for (int[] offset : LOCAL_SCAN_RINGS[radius]) {
                    int dx = offset[0];
                    int dz = offset[1];
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    if (dy == -1 && x == pathStart.x && z == pathStart.z) {
                        continue; // dont mine what we're supported by, but not directly standing on
                    }
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    if (stateTolerated.contains(new BetterBlockPos(x, y, z))) {
                        continue; // replacing this one reproduces the same state, so leave it standing
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    // Same rule as in assemble: a scaffolding block may only be left alone where the target wants air.
                    // Where the target wants a real block the scaffold is an obstruction and gets broken like any other.
                    boolean ourScaffolding = desired.isAir() && PlacedBlocks.isOurs(new BetterBlockPos(x, y, z));
                    if (!(curr.getBlock() instanceof AirBlock) && !(curr.getBlock() == Blocks.WATER || curr.getBlock() == Blocks.LAVA) && !valid(curr, desired, false)
                            && !ourScaffolding) {
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        // When the block that is standing there is already the right block, mining it only makes room
                        // for the same block to come back: the state is decided by the world, not by how it is placed.
                        // Count the rounds and stop at the limit instead of mining the same cell for the whole build.
                        if (curr.getBlock() == desired.getBlock()) {
                            int rounds = repairRounds.merge(pos, 1, Integer::sum);
                            if (rounds > REPAIR_ROUND_LIMIT) {
                                repairRounds.remove(pos);
                                if (stateTolerated.add(pos)) {
                                    BaritoneLog.log("STATE", "%s rounds of mine-and-replace at %s never produced %s (still %s), so it is accepted as it is",
                                            rounds, pos, desired, curr);
                                }
                                continue;
                            }
                        }
                        Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
                        if (rot.isPresent()) {
                            if (!pos.equals(lastBreakLogged)) {
                                lastBreakLogged = pos;
                                BaritoneLog.log("BREAK", "mining %s: want %s, found %s, aiming %s (layer %s)",
                                        pos, desired, curr, rot.get(), layerScheduler == null ? "<none>" : layerScheduler.toString());
                            }
                            return Optional.of(new Tuple<>(pos, rot.get()));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    public static class Placement {

        private final int hotbarSelection;
        private final BlockPos placeAgainst;
        private final Direction side;
        private final Rotation rot;
        /**
         * Whether this state only comes out right if the click really lands on {@link #side}.
         * <p>
         * A log has a facing, and the facing is decided by the face the click hits: aiming "close enough" and clicking
         * anyway produces the same block with the wrong axis, which the builder then has to mine and redo. Those wrong
         * placements are where the endless mine-and-replace churn came from, so for these states the click waits for a
         * confirmed hit instead of a roughly right look direction.
         */
        private final boolean strictAim;

        /**
         * The state the schematic wants at the target cell.
         * <p>
         * Kept on the placement so the click itself can be checked against it: hitting the right block on the right
         * face is only half of it, the state that face would produce has to be the wanted one as well.
         */
        private final BlockState desired;

        public Placement(int hotbarSelection, BlockPos placeAgainst, Direction side, Rotation rot, boolean strictAim, BlockState desired) {
            this.hotbarSelection = hotbarSelection;
            this.placeAgainst = placeAgainst;
            this.side = side;
            this.rot = rot;
            this.strictAim = strictAim;
            this.desired = desired;
        }
    }

    private Optional<Placement> searchForPlacables(BuilderCalculationContext bcc, List<BlockState> desirableOnHotbar) {
        BetterBlockPos center = ctx.playerFeet();
        // Closest ring first, nearest height first, so the block that is actually adjacent gets placed before the
        // builder wanders off to one that is merely in range.
        for (int radius = 0; radius <= LOCAL_SCAN_RADIUS; radius++) {
            for (int dy : VERTICAL_ORDER_PLACE) {
                for (int[] offset : LOCAL_SCAN_RINGS[radius]) {
                    int dx = offset[0];
                    int dz = offset[1];
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (MovementHelper.isReplaceable(x, y, z, curr, bcc.bsi) && !valid(curr, desired, false)) {
                        if (dy == 1 && bcc.bsi.get0(x, y + 1, z).getBlock() instanceof AirBlock) {
                            continue;
                        }
                        desirableOnHotbar.add(desired);
                        BetterBlockPos candidate = new BetterBlockPos(x, y, z);
                        if (playerIsStandingIn(x, y, z)) {
                            // The client refuses to place a block inside the player, so aiming at this one forever can
                            // never work. Move on; the goal machinery will walk somewhere else and come back to it.
                            continue;
                        }
                        if (aimGiveUp.contains(candidate)) {
                            // No look angle reached this face within the give-up window, so retrying it every tick
                            // only holds the camera still. A resume clears the set and gives it another chance.
                            continue;
                        }
                        Optional<Placement> opt = possibleToPlace(desired, x, y, z, bcc.bsi);
                        if (opt.isPresent()) {
                            // Finding a placement is not the same as the click landing: the click is issued later in the
                            // tick, only when the player is standing still and grounded. Counting this as an attempt and
                            // giving up after a dozen of them abandoned perfectly good positions, which showed up as
                            // blocks that never got placed at all. It stays a plain "we have work here" signal.
                            placementStruggles.remove(candidate);
                            PlacedBlocks.forget(candidate);
                            if (loggedPlacements.add(candidate)) {
                                BaritoneLog.log("PLACE", "clicking %s at %s: against %s side %s, hotbar slot %s, aim %s",
                                        desired, candidate, opt.get().placeAgainst, opt.get().side, opt.get().hotbarSelection, opt.get().rot);
                            }
                            return opt;
                        }
                        // A position that stays reachable but never becomes placeable is what leaves the builder
                        // standing next to a single block forever, and a directional block that no reachable side can
                        // produce is the usual cause. Counting the attempts lets it be left alone instead.
                        placementStruggles.merge(candidate, 1, Integer::sum);
                    }
                }
            }
        }
        if (tickLogCounter % 200 == 0) {
            BaritoneLog.log("PLACE", "nothing placeable within %s blocks of %s; wanted states=%s, positions that keep failing=%s",
                    LOCAL_SCAN_RADIUS, center, desirableOnHotbar.size(), placementStruggles.size());
        }
        return Optional.empty();
    }

    /**
     * @return whether this position holds a throwaway block the pathfinder placed for itself, in a cell the target
     * wants empty
     * <p>
     * Only those cells may be left alone. Wherever the target wants a real block the scaffolding is an obstruction and
     * has to be broken like any other: pretending it is fine leaves that block unplaced and shows up as a hole in the
     * build, which is worse than the flicker it was meant to avoid.
     */
    private boolean isOurScaffolding(BetterBlockPos pos, BuilderCalculationContext bcc, BlockState current) {
        if (!PlacedBlocks.isOurs(pos)) {
            return false;
        }
        BlockState wanted = bcc.getSchematic(pos.x, pos.y, pos.z, current);
        return wanted != null && wanted.isAir();
    }

    /**
     * @return whether the player's own bounding box is inside this block position, which makes the client refuse the
     * click and would otherwise keep the builder aiming at it forever
     */
    private boolean playerIsStandingIn(int x, int y, int z) {
        var player = ctx.player();
        if (player == null) {
            return false;
        }
        return player.getBoundingBox().intersects(x, y, z, x + 1, y + 1, z + 1);
    }

    /**
     * Gives up on the positions that have been reachable for a long time but never became placeable.
     * <p>
     * A directional block whose wanted orientation no reachable side can produce would otherwise be retried every tick
     * forever: the goal that asks the builder to walk over there never goes away, so no layer ever reports that it has
     * nothing left to do. Retiring such a position lets the rest of the band (and then the rest of the build) make
     * progress, and the message says which block was left behind so it is not a silent hole.
     */
    private void retireImpossiblePlacements() {
        if (placementStruggles.isEmpty()) {
            return;
        }
        placementStruggles.entrySet().removeIf(entry -> {
            if (entry.getValue() < PLACEMENT_STRUGGLE_LIMIT) {
                return false;
            }
            BetterBlockPos pos = entry.getKey();
            if (abandonedPlacements.add(pos)) {
                BlockState state = schematic == null ? null : placeAt(pos.x, pos.y, pos.z, ctx.world().getBlockState(pos));
                logDirect(String.format(
                        "Cannot place %s at %s %s %s after %s attempts, leaving it for the rest of this build. "
                                + "Stand somewhere else and resume to try it again.",
                        state == null ? "a block" : state.getBlock().getName().getString(),
                        pos.x, pos.y, pos.z, entry.getValue()));
            }
            return true;
        });
    }

    public boolean placementPlausible(BlockPos pos, BlockState state) {
        VoxelShape voxelshape = state.getCollisionShape(ctx.world(), pos);
        return voxelshape.isEmpty() || ctx.world().isUnobstructed(null, voxelshape.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    /**
     * Compares the cell that was clicked last tick against the state that was asked for, and learns from the answer.
     * <p>
     * If the block that came out is the right block with a state that cannot be produced by placing it there, then
     * that state is decided by the world rather than by the click, and no amount of mining and replacing will change
     * it. The properties that differ are written off for that block, so later comparisons stop calling the cell wrong.
     */
    private void verifyPendingPlacement() {
        BetterBlockPos pos = pendingPlacementPos;
        BlockState wanted = pendingPlacementWanted;
        pendingPlacementPos = null;
        pendingPlacementWanted = null;
        if (pos == null || wanted == null) {
            return;
        }
        BlockState now = ctx.world().getBlockState(pos);
        if (now.getBlock() != wanted.getBlock() || valid(now, wanted, false)) {
            return; // nothing landed yet, or it landed exactly as asked
        }
        Set<Property<?>> learned = derivedProperties.computeIfAbsent(wanted.getBlock(), block -> new HashSet<>());
        List<String> differing = new ArrayList<>();
        for (Property<?> property : wanted.getProperties()) {
            if (!samePropertyValue(now, wanted, property)) {
                differing.add(property.getName());
                learned.add(property);
            }
        }
        if (!differing.isEmpty()) {
            BaritoneLog.log("STATE", "placing %s at %s produced %s: %s cannot be set by the click, so this block type will not be replaced over it again",
                    wanted, pos, now, differing);
        }
    }

    /**
     * @return whether both states agree on one property, without caring which property type it is
     */
    private static boolean samePropertyValue(BlockState first, BlockState second, Property<?> property) {
        return propertyValue(first, property).equals(propertyValue(second, property));
    }

    private static <T extends Comparable<T>> Object propertyValue(BlockState state, Property<T> property) {
        return state.getValue(property);
    }

    /**
     * Keeps the progress hint in step with the build.
     * <p>
     * The builder used to announce "starting at layer 1/131" once, in the chat, and that line then sat there for the
     * rest of the session: after the build finished it still said it was starting at layer 1. A status that outlives
     * the state it describes is worse than no status, so the running description is sent to the action bar instead,
     * where it fades on its own, and every state change (band finished, band set aside, build done) is a chat line of
     * its own. Nothing is left behind to go stale.
     */
    private void offerStatus(boolean force) {
        if (ctx.player() == null) {
            return;
        }
        if (!force) {
            if (statusCountdown > 0) {
                statusCountdown--;
                return;
            }
        }
        statusCountdown = STATUS_INTERVAL_TICKS;
        String line = statusLine();
        if (line.equals(lastStatusLine)) {
            return;
        }
        lastStatusLine = line;
        ctx.player().displayClientMessage(Component.literal(line), true);
    }

    private String statusLine() {
        StringBuilder builder = new StringBuilder("[Baritone] ");
        if (layerScheduler != null && slicingActive()) {
            builder.append(layerScheduler).append(" · ");
        }
        int pending = incorrectPositions == null ? 0 : incorrectPositions.size();
        builder.append(pending).append(pending == 1 ? " block left" : " blocks left");
        if (paused) {
            builder.append(" · paused");
        }
        return builder.toString();
    }

    /**
     * Clears the progress hint, for when the build stops being the thing that is happening.
     */
    private void clearStatus() {
        if (ctx.player() == null) {
            return;
        }
        lastStatusLine = null;
        ctx.player().displayClientMessage(Component.empty(), true);
    }

    /**
     * Checks whether the band the builder is working on is really finished, by looking at every cell in it.
     * <p>
     * {@code recalc} not finding anything is not the same thing: it looks at a radius around the player, it stops
     * early once enough wrong positions are known, and it trims everything that is far away. Any of those makes a
     * band with plenty of blocks left look empty, and the builder then moved on to the next band, which is why a long
     * build jumped over a layer and came back to it much later (or never). Before a band is declared finished, it is
     * swept in full once, and only an empty sweep counts.
     *
     * @return whether every cell of the current band already matches the schematic
     */
    private boolean bandIsComplete(BuilderCalculationContext bcc) {
        if (!slicingActive()) {
            return true;
        }
        int minY = Math.max(0, layerScheduler.sliceMinY());
        int maxY = Math.min(schematic.heightY() - 1, layerScheduler.sliceMaxY());
        for (int y = minY; y <= maxY; y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + origin.getX();
                    int blockY = y + origin.getY();
                    int blockZ = z + origin.getZ();
                    BlockState current = bcc.bsi.get0(blockX, blockY, blockZ);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    BlockState desired = bcc.getSchematic(blockX, blockY, blockZ, current);
                    if (desired == null) {
                        continue;
                    }
                    if (!valid(current, desired, false)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private Optional<Placement> possibleToPlace(BlockState toPlace, int x, int y, int z, BlockStateInterface bsi) {
        // Only a block whose state depends on how it is placed is worth aiming at from four directions; for everything
        // else a sweep would just repeat the same ray trace four times.
        boolean directional = hasPlacementOrientation(toPlace);
        for (Direction against : Direction.values()) {
            BetterBlockPos placeAgainstPos = new BetterBlockPos(x, y, z).relative(against);
            BlockState placeAgainstState = bsi.get0(placeAgainstPos);
            if (MovementHelper.isReplaceable(placeAgainstPos.x, placeAgainstPos.y, placeAgainstPos.z, placeAgainstState, bsi)) {
                continue;
            }
            if (!toPlace.canSurvive(ctx.world(), new BetterBlockPos(x, y, z))) {
                continue;
            }
            if (!placementPlausible(new BetterBlockPos(x, y, z), toPlace)) {
                continue;
            }
            VoxelShape shape = placeAgainstState.getShape(ctx.world(), placeAgainstPos);
            if (shape.isEmpty()) {
                continue;
            }
            AABB aabb = shape.bounds();
            for (Vec3 placementMultiplier : aabbSideMultipliers(against)) {
                double placeX = placeAgainstPos.x + aabb.minX * placementMultiplier.x + aabb.maxX * (1 - placementMultiplier.x);
                double placeY = placeAgainstPos.y + aabb.minY * placementMultiplier.y + aabb.maxY * (1 - placementMultiplier.y);
                double placeZ = placeAgainstPos.z + aabb.minZ * placementMultiplier.z + aabb.maxZ * (1 - placementMultiplier.z);
                Rotation aim = RotationUtils.calcRotationFromVec3d(RayTraceUtils.inferSneakingEyePosition(ctx.player()), new Vec3(placeX, placeY, placeZ), ctx.playerRotations());
                // the sweep only helps a block whose placement state depends on where the builder looks
                List<Rotation> rotations = directional ? placementRotations(aim) : Collections.singletonList(aim);
                for (Rotation rot : rotations) {
                    Rotation actualRot = baritone.getLookBehavior().getAimProcessor().peekRotation(rot);
                    HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), actualRot, ctx.playerController().getBlockReachDistance(), true);
                    if (result == null || result.getType() != HitResult.Type.BLOCK) {
                        continue;
                    }
                    BlockHitResult blockHit = (BlockHitResult) result;
                    // the sweep turns away from the click point on purpose, so a candidate only survives when the turned
                    // aim still hits the exact block and side that was picked
                    if (!blockHit.getBlockPos().equals(placeAgainstPos) || blockHit.getDirection() != against.getOpposite()) {
                        continue;
                    }
                    OptionalInt hotbar = hasAnyItemThatWouldPlace(toPlace, result, actualRot);
                    if (hotbar.isPresent()) {
                        return Optional.of(new Placement(hotbar.getAsInt(), placeAgainstPos, against.getOpposite(), rot, hasPlacementOrientation(toPlace), toPlace));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * @return the look directions worth trying for one candidate placement point, the plain aim first
     * <p>
     * Where the builder looks decides what a directional block becomes while it is placed: a hopper ends up facing away
     * from the player, a piston and a dispenser face the same way the player does, a dropper the same, so looking
     * straight at the click point can be the one angle that produces a state the schematic does not ask for. Sweeping
     * the four horizontal directions gives the block a chance to come out in the wanted orientation from the very spot
     * the builder already stands on. Each turned direction is re-traced and dropped unless it still hits the same block
     * and side, so nothing can be placed against the wrong face because of this.
     */
    private static List<Rotation> placementRotations(Rotation aim) {
        List<Rotation> rotations = new ArrayList<>(4);
        rotations.add(aim);
        for (int quarter = 1; quarter < 4; quarter++) {
            rotations.add(new Rotation(aim.getYaw() + 90 * quarter, aim.getPitch()));
        }
        return rotations;
    }

    private OptionalInt hasAnyItemThatWouldPlace(BlockState desired, HitResult result, Rotation rot) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            // Building the placement context and asking the block for its placement state is not cheap, and this runs
            // for every candidate position of the local scan. When the slot holds a different block than the one the
            // schematic wants, no orientation can make it match, so there is nothing worth simulating here.
            if (approxPlaceable != null && i < approxPlaceable.size()
                    && approxPlaceable.get(i).getBlock() != desired.getBlock()) {
                continue;
            }
            BlockState wouldBePlaced = simulatePlacement(stack, result, rot);
            if (wouldBePlaced != null && valid(wouldBePlaced, desired, true)) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Ask the block itself what it turns into when the item in {@code stack} is placed against the clicked block while
     * the player looks along {@code rot}.
     * <p>
     * This is the one place that knows the placement rule of every block, vanilla or modded, so both the placement
     * search and the choice of which side to walk to go through it instead of re-deriving one rule per block.
     *
     * @return the state that would be placed, or null when the block declines to be placed there
     */
    private BlockState simulatePlacement(ItemStack stack, HitResult result, Rotation rot) {
        BlockPlaceContext context = placeContext(stack, result);
        BlockState placed = simulatePlacementState(context, rot);
        // the position itself has to accept a block as well, not just the state it would turn into
        if (placed == null || !withPlayerRotation(rot, context::canPlace)) {
            return null;
        }
        return placed;
    }

    /**
     * @return the state the block would take, ignoring whether the spot itself can currently be built on
     * <p>
     * Used to learn which sides a state can be reached from: that answer belongs to the block's own rule and must not
     * depend on what currently stands next to one particular position, so it stays valid for every other position and
     * can be cached.
     */
    private BlockState simulatePlacementState(BlockPlaceContext context, Rotation rot) {
        return withPlayerRotation(rot, () -> {
            if (!(context.getItemInHand().getItem() instanceof BlockItem blockItem)) {
                return null;
            }
            return blockItem.getBlock().getStateForPlacement(context);
        });
    }

    /**
     * Runs {@code action} while the player faces {@code rot}, then restores the real orientation.
     * <p>
     * Placement rules read the player's facing (a hopper points away from it, a piston towards it), and the client only
     * sends its own orientation, so that is what has to be borrowed to ask the question.
     */
    private <T> T withPlayerRotation(Rotation rot, Supplier<T> action) {
        float originalYaw = ctx.player().getYRot();
        float originalPitch = ctx.player().getXRot();
        ctx.player().setYRot(rot.getYaw());
        ctx.player().setXRot(rot.getPitch());
        try {
            return action.get();
        } finally {
            ctx.player().setYRot(originalYaw);
            ctx.player().setXRot(originalPitch);
        }
    }

    private BlockPlaceContext placeContext(ItemStack stack, HitResult result) {
        return new BlockPlaceContext(new UseOnContext(
                ctx.world(),
                ctx.player(),
                InteractionHand.MAIN_HAND,
                stack,
                (BlockHitResult) result
        ) {}); // that {} gives us access to a protected constructor lmfao
    }

    private static int[][][] buildLocalScanRings() {
        int[][][] rings = new int[LOCAL_SCAN_RADIUS + 1][][];
        for (int radius = 0; radius <= LOCAL_SCAN_RADIUS; radius++) {
            List<int[]> offsets = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == radius) {
                        offsets.add(new int[]{dx, dz});
                    }
                }
            }
            rings[radius] = offsets.toArray(new int[0][]);
        }
        return rings;
    }

    private static int[] verticalOffsetOrder(int min, int max) {
        int[] order = new int[max - min + 1];
        int index = 0;
        for (int distance = 0; index < order.length; distance++) {
            if (distance >= min && distance <= max) {
                order[index++] = distance;
            }
            if (distance != 0 && -distance >= min && -distance <= max) {
                order[index++] = -distance;
            }
        }
        return order;
    }

    private static Vec3[] aabbSideMultipliers(Direction side) {
        switch (side) {
            case UP:
                return new Vec3[]{new Vec3(0.5, 1, 0.5), new Vec3(0.1, 1, 0.5), new Vec3(0.9, 1, 0.5), new Vec3(0.5, 1, 0.1), new Vec3(0.5, 1, 0.9)};
            case DOWN:
                return new Vec3[]{new Vec3(0.5, 0, 0.5), new Vec3(0.1, 0, 0.5), new Vec3(0.9, 0, 0.5), new Vec3(0.5, 0, 0.1), new Vec3(0.5, 0, 0.9)};
            case NORTH:
            case SOUTH:
            case EAST:
            case WEST:
                double x = side.getStepX() == 0 ? 0.5 : (1 + side.getStepX()) / 2D;
                double z = side.getStepZ() == 0 ? 0.5 : (1 + side.getStepZ()) / 2D;
                return new Vec3[]{new Vec3(x, 0.25, z), new Vec3(x, 0.75, z)};
            default: // null
                throw new IllegalStateException("Unexpected side " + side);
        }
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        return onTick(calcFailed, isSafeToCancel, 0);
    }

    private PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel, int recursions) {
        if (recursions > 100) { // onTick calls itself, don't crash
            return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
        }
        if (recursions == 0 && wholeBandCooldown > 0) {
            // only the first pass of a tick counts, the recursive retries below happen within the same tick
            wholeBandCooldown--;
        }
        refreshApproxPlaceable();
        // This is not the placement rate. It only keeps the builder from placing on the same tick it is mining:
        // a forced left click sets it to 5 and the gate below skips placement until it drains. The actual rate
        // is rightClickSpeed in BlockPlaceHelper, which delays every click by rightClickSpeed - 1 ticks.
        if (baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)) {
            ticks = 5;
        } else {
            ticks--;
        }
        baritone.getInputOverrideHandler().clearAllKeys();
        if (recursions == 0) {
            // calcFailed means the path calculation for the goal handed out on the previous tick came back empty. That
            // used to be ignored completely, which is what let a build idle forever while asking for an unreachable
            // spot: nothing failed loudly, nothing retried differently, the same goal just went out again.
            if (calcFailed) {
                calcFailStreak++;
            } else {
                calcFailStreak = 0;
            }
            logTickState(calcFailed, isSafeToCancel);
            offerStatus(false);
        }
        if (paused) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (realSchematic == null) {
            realSchematic = schematic;
        }
        if (realSchematic == null) {
            // Another process took control and onLostControl already ran, but this tick was still in flight. Reporting
            // inactive here is what keeps PathingControlManager happy; trying to slice a target that is gone is not.
            BaritoneLog.log("BUILD", "ticked without a target, stopping");
            onLostControl();
            return null;
        }
        if (Baritone.settings().buildLayerSlicing.value && stopAtHeight > 0) {
            boolean freshScheduler = false;
            if (layerScheduler == null) {
                layerScheduler = createLayerScheduler();
                freshScheduler = layerScheduler != null;
            }
            if (layerScheduler != null) {
                // No chat line here on purpose: "starting at layer 1/131" printed once and left in the chat is what
                // looked like a stuck status long after the build had moved on. The running position goes to the
                // action bar (it fades by itself) and every band change gets its own chat line.
                if (freshScheduler) {
                    BaritoneLog.log("BUILD", "layered build starts at %s", layerScheduler);
                }
                schematic = new LayerSliceSchematic(realSchematic, layerScheduler);
                if (freshScheduler) {
                    offerStatus(true);
                }
            }
        } else if (Baritone.settings().buildInLayers.value) {
            ISchematic realSchematic = this.realSchematic; // wrap this properly, dont just have the inner class refer to the builderprocess.this
            int minYInclusive;
            int maxYInclusive;
            // layer = 0 should be nothing
            // layer = realSchematic.heightY() should be everything
            if (Baritone.settings().layerOrder.value) { // top to bottom
                maxYInclusive = realSchematic.heightY() - 1;
                minYInclusive = realSchematic.heightY() - layer * Baritone.settings().layerHeight.value;
            } else {
                maxYInclusive = layer * Baritone.settings().layerHeight.value - 1;
                minYInclusive = 0;
            }
            schematic = new ISchematic() {
                @Override
                public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
                    return realSchematic.desiredState(x, y, z, current, BuilderProcess.this.approxPlaceable);
                }

                @Override
                public boolean inSchematic(int x, int y, int z, BlockState currentState) {
                    return ISchematic.super.inSchematic(x, y, z, currentState) && y >= minYInclusive && y <= maxYInclusive && realSchematic.inSchematic(x, y, z, currentState);
                }

                @Override
                public void reset() {
                    realSchematic.reset();
                }

                @Override
                public int widthX() {
                    return realSchematic.widthX();
                }

                @Override
                public int heightY() {
                    return realSchematic.heightY();
                }

                @Override
                public int lengthZ() {
                    return realSchematic.lengthZ();
                }
            };
        } else if (schematic != realSchematic) {
            // slicing or layered building was turned off part way through, so stop restricting the schematic
            schematic = realSchematic;
        }
        if (recursions == 0 && calcFailStreak >= CALC_FAIL_STREAK_LIMIT) {
            // The pathfinder has been unable to produce anything for the same goal for a few seconds straight, so the
            // blocks behind that goal cannot be walked to from where the builder is standing. Set them aside and look
            // at the rest of the band instead of repeating the same doomed request until the player nudges the mouse.
            escapeStall("the pathfinder failed " + calcFailStreak + " times in a row for " + describeGoal(lastGoalSent));
        }
        BuilderCalculationContext bcc = new BuilderCalculationContext();
        if (!recalc(bcc)) {
            if (slicingActive()) {
                if (bandIsComplete(bcc)) {
                    if (layerScheduler.advance()) {
                        logDirect("Layer finished, moving on to " + layerScheduler);
                        BaritoneLog.log("BUILD", "band complete, cursor now %s", layerScheduler);
                        incorrectPositions = null; // the next layer has to be scanned again, from the bottom of its own band
                        aimTicks.clear();
                        aimGiveUp.clear();
                        offerStatus(true);
                        return onTick(calcFailed, isSafeToCancel, recursions + 1);
                    }
                } else {
                    // The scan around the player found nothing, but the band is not done: the remaining cells are out
                    // of scan range, or the scan stopped early. Setting the band aside (instead of calling it
                    // finished) keeps the wrong blocks on the books, and they get another turn once the rest of the
                    // build has moved the builder closer.
                    BaritoneLog.log("BUILD", "band %s is not complete yet, but nothing in it can be built from %s; setting it aside", layerScheduler, ctx.playerFeet());
                    logDirect("Layer " + layerScheduler + " still has blocks left, but none of them can be built from here yet; coming back to it");
                    if (layerScheduler.deferSlice()) {
                        incorrectPositions = null;
                        aimTicks.clear();
                        aimGiveUp.clear();
                        offerStatus(true);
                        return onTick(calcFailed, isSafeToCancel, recursions + 1);
                    }
                    // Every band is waiting for something. Repeating the same scan tick after tick would spin, so the
                    // build stops and says why instead of pretending it is finished.
                    layerScheduler.clearDeferred();
                    logDirect("Every layer is waiting for something (material, reach or a support to stand on), so the build is paused");
                    logNotification("Every layer is waiting for something, build paused", true);
                    paused = true;
                    clearStatus();
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            } else if (Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < stopAtHeight) {
                logDirect("Starting layer " + layer);
                layer++;
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            Vec3i repeat = Baritone.settings().buildRepeat.value;
            int max = Baritone.settings().buildRepeatCount.value;
            numRepeats++;
            if (repeat.equals(new Vec3i(0, 0, 0)) || (max != -1 && numRepeats >= max)) {
                logDirect("Done building" + (layerScheduler == null ? "" : ", last band was " + layerScheduler));
                if (Baritone.settings().notificationOnBuildFinished.value) {
                    logNotification("Done building", false);
                }
                clearStatus();
                onLostControl();
                return null;
            }
            // build repeat time
            layer = 0;
            if (layerScheduler != null) {
                layerScheduler.reset();
            }
            origin = new BlockPos(origin).offset(repeat);
            if (!Baritone.settings().buildRepeatSneaky.value) {
                schematic.reset();
            }
            logDirect("Repeating build in vector " + repeat + ", new origin is " + origin);
            return onTick(calcFailed, isSafeToCancel, recursions + 1);
        }
        if (Baritone.settings().distanceTrim.value) {
            trim();
        }

        // Placing is tried before breaking. The other order made the builder mine every wrong-state block inside its
        // reach on the way past, walk off with the holes still open, and only come back for them near the end of the
        // layer - which is exactly "it breaks a few blocks for no reason and refills them much later".
        List<BlockState> desirableOnHotbar = new ArrayList<>();
        Optional<Placement> toPlace = searchForPlacables(bcc, desirableOnHotbar);
        retireImpossiblePlacements();
        verifyPendingPlacement();
        offerStatus(false);
        if (toPlace.isPresent() && isSafeToCancel && ctx.player().onGround() && ticks <= 0) { // see above: the gate is mining, the rate is rightClickSpeed
            Placement placement = toPlace.get();
            Rotation rot = placement.rot;
            baritone.getLookBehavior().updateTarget(rot, true);
            ctx.player().getInventory().setSelectedSlot(placement.hotbarSelection);
            // Every placement is made while sneaking. Removing it was tried and reverted: without the sneak the click
            // lands differently often enough (the support eats it, the player edge-slides while the click goes out)
            // that blocks came out in the wrong state and had to be mined and redone, which cost far more time than the
            // one tick the crouch takes.
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            BetterBlockPos target = new BetterBlockPos(placement.placeAgainst.relative(placement.side));
            BlockHitResult over = ctx.objectMouseOver() instanceof BlockHitResult hit ? hit : null;
            boolean onWantedFace = over != null
                    && over.getBlockPos().equals(placement.placeAgainst)
                    && over.getDirection() == placement.side
                    && ctx.isLookingAt(placement.placeAgainst);
            // A click on the right block but the wrong face produces the block in the wrong orientation, and then the
            // builder mines it and tries again. The face is not the requirement, the state is: run the placement on
            // the face that is actually under the crosshair and only click when the state that comes out of it is the
            // one the schematic asked for. Otherwise hold the aim and let the camera settle, and give up on this
            // target only after the aim has had a fair chance to get there.
            boolean stateWouldMatch = onWantedFace && hasAnyItemThatWouldPlace(placement.desired, over, ctx.playerRotations()).isPresent();
            if (stateWouldMatch) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                pendingPlacementPos = target;
                pendingPlacementWanted = placement.desired;
                aimTicks.remove(target);
            } else if (onWantedFace) {
                int aimed = aimTicks.merge(target, 1, Integer::sum);
                if (aimed % 20 == 0) {
                    BaritoneLog.log("AIM", "holding the click at %s: the face under the crosshair would not produce %s yet (tick %s)",
                            target, placement.desired, aimed);
                }
                if (aimed >= AIM_GIVE_UP_TICKS) {
                    aimTicks.remove(target);
                    aimGiveUp.add(target);
                    placementStruggles.merge(target, 40, Integer::sum);
                    BaritoneLog.log("AIM", "no look reaches %s after %s ticks (%s, against %s), leaving it for now",
                            target, aimed, placement.desired, placement.placeAgainst);
                }
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // Breaking waits until the player has arrived: while a path is being followed, whatever is in the way is the
        // pathfinder's business (it prices breaking in itself), and a builder that mines mid-walk is only leaving holes
        // behind it. Standing still at the work, the wrong block right next to it is worth clearing.
        if (!baritone.getPathingBehavior().isPathing()) {
            Optional<Tuple<BetterBlockPos, Rotation>> toBreak = toBreakNearPlayer(bcc);
            if (toBreak.isPresent() && isSafeToCancel && ctx.player().onGround()) {
                // we'd like to pause to break this block
                // only change look direction if it's safe (don't want to fuck up an in progress parkour for example
                Rotation rot = toBreak.get().getB();
                BetterBlockPos pos = toBreak.get().getA();
                baritone.getLookBehavior().updateTarget(rot, true);
                MovementHelper.switchToBestToolFor(ctx, bcc.get(pos));
                if (ctx.player().isCrouching()) {
                    // really horrible bug where a block is visible for breaking while sneaking but not otherwise
                    // so you can't see it, it goes to place something else, sneaks, then the next tick it tries to break
                    // and is unable since it's unsneaked in the intermediary tick
                    baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                }
                if (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                }
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }

        if (Baritone.settings().allowInventory.value) {
            ArrayList<Integer> usefulSlots = new ArrayList<>();
            List<BlockState> noValidHotbarOption = new ArrayList<>();
            outer:
            for (BlockState desired : desirableOnHotbar) {
                for (int i = 0; i < 9; i++) {
                    if (couldPlaceFrom(approxPlaceable.get(i), desired)) {
                        usefulSlots.add(i);
                        continue outer;
                    }
                }
                noValidHotbarOption.add(desired);
            }

            outer:
            for (int i = 9; i < 36; i++) {
                for (BlockState desired : noValidHotbarOption) {
                    if (couldPlaceFrom(approxPlaceable.get(i), desired)) {
                        if (!baritone.getInventoryBehavior().attemptToPutOnHotbar(i, usefulSlots::contains)) {
                            // awaiting inventory move, so pause
                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                        break outer;
                    }
                }
            }
        }

        // Only the pocket the builder is standing next to is handed to the pathfinder, so that the route it plans is
        // a short local one instead of a trip to the far corner of the layer. With allowInventory the whole inventory
        // counts rather than just the hotbar: a block sitting in the main inventory is one inventory move away from
        // being placeable, and counting only the hotbar is what made the builder call it a missing material.
        List<BlockState> reachablePlaceable = Baritone.settings().allowInventory.value ? approxPlaceable : hotbarOnly(approxPlaceable);
        Collection<BetterBlockPos> sweepPocket = selectSweepPocket();
        Goal goal = assemble(bcc, reachablePlaceable, false, sweepPocket);
        if (goal == null && sweepPocket != incorrectPositions) {
            // this pocket has nothing left that we can do from here, so let the rest of the layer have a turn
            goal = assemble(bcc, reachablePlaceable);
        }
        if (goal == null) {
            goal = assemble(bcc, approxPlaceable, true); // we're far away, so assume that we have our whole inventory to recalculate placeable properly
        }
        if (goal == null) {
            // The pending set is trimmed to what is around the player and the pocket narrows it further, so a band that
            // looks impossible from here can still have plenty of work left a few blocks away. Look at all of it before
            // concluding that the build cannot continue.
            goal = assembleWholeBand(bcc);
        }
        if (goal == null) {
            if (Baritone.settings().skipFailedLayers.value && slicingActive()) {
                logDirect("Skipping layer that I cannot construct! " + layerScheduler);
                if (layerScheduler.advance()) {
                    incorrectPositions = null;
                    return onTick(calcFailed, isSafeToCancel, recursions + 1);
                }
                logDirect("No further layers are left to construct");
            } else if (Baritone.settings().skipFailedLayers.value && Baritone.settings().buildInLayers.value && layer * Baritone.settings().layerHeight.value < realSchematic.heightY()) {
                logDirect("Skipping layer that I cannot construct! Layer #" + layer);
                layer++;
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            // Nothing in this band can be built right now, but that is a statement about this band and not about the
            // build. Set it aside and give the remaining bands a turn instead of freezing the whole job over a single
            // block; every band is treated the same way, and only when none of them has anything left to do does the
            // build actually pause.
            if (slicingActive() && layerScheduler.deferSlice()) {
                logDirect("Nothing to do in that layer yet, moving on to " + layerScheduler);
                incorrectPositions = null;
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            logDirect("Unable to do it. Pausing. resume to resume, cancel to cancel");
            BaritoneLog.log("PAUSE", "no goal could be assembled: layer=%s pending=%s abandoned=%s struggles=%s feet=%s. The ASSEMBLE/MISSING/PLACE lines above say why every candidate was rejected.",
                    layerScheduler == null ? "<none>" : layerScheduler.toString(), pendingCount(),
                    abandonedPlacements.size(), placementStruggles.size(), feetForLog());
            paused = true;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        lastGoalSent = goal;
        if (recursions == 0) {
            var pathingBehavior = baritone.getPathingBehavior();
            boolean pathing = pathingBehavior.isPathing();
            boolean clicking = baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)
                    || baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_RIGHT);
            BetterBlockPos feet = ctx.playerFeet();
            // The builder's goals are "walk next to the block I want to touch". When the player already stands next to
            // that block the goal counts as satisfied, so PathingBehavior.secretInternalSetGoalAndPath returns without
            // ever calculating a path: no path, no click, no error, and the build sits there until the player happens to
            // move. That is the "it only continues after I walk somewhere" report, so this case gets a much shorter
            // fuse than a goal that is simply hard to reach.
            boolean goalSatisfied = goal != null && goal.isInGoal(feet);
            if (!feet.equals(lastProgressFeet)) {
                lastProgressFeet = feet;
                stillTicks = 0;
                idleTicks = 0;
            } else {
                stillTicks++;
            }
            if (!pathing && !clicking) {
                int limit = goalSatisfied ? SATISFIED_STALL_LIMIT : IDLE_STALL_LIMIT;
                if (++idleTicks >= limit) {
                    escapeStall(goalSatisfied
                            ? "standing at " + feet + " already satisfies " + describeGoal(goal) + " but nothing could be done from there"
                            : "no progress for " + idleTicks + " ticks (no path, no clicks) on goal " + describeGoal(goal));
                    return onTick(calcFailed, isSafeToCancel, recursions + 1);
                }
            } else if (pathing && stillTicks >= STILL_TICK_LIMIT) {
                // A path exists and is being followed, but the player has not moved a single block for ten seconds:
                // the executor is walking into terrain it cannot handle. Cancel the segment so the next calculation is
                // free to pick a different route, and set aside the target that led into the dead end.
                escapeStall("the current path has not moved the player for " + stillTicks + " ticks, cancelling the segment");
                pathingBehavior.forceCancel();
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
        }
        return new PathingCommandContext(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, bcc);
    }

    /**
     * A short, readable name for a goal, shared with the path event log.
     */
    private static String describeGoal(Goal goal) {
        return BaritoneLog.goal(goal);
    }

    private int pendingCount() {
        return incorrectPositions == null ? 0 : incorrectPositions.size();
    }

    /**
     * Forgets what the builder has learned about clicks and log throttling.
     * <p>
     * A resume, a new build or a lost control are all moments where the player may have walked somewhere else, so the
     * click counters and the scaffolding registry must not carry over: an old "give up on this position" must not stop
     * a retry, and a scaffold from ten minutes ago must not stop the builder from digging out a block that really is in
     * the way.
     */
    private void resetPlacementTracking() {
        loggedPlacements.clear();
        lastPlannedSignature = null;
        lastBreakLogged = null;
        lastAssembleSummary = null;
        PlacedBlocks.clear();
        // The aiming and repair bookkeeping is tied to one stance and one set of blocks in front of the player. A new
        // build, a resume, or losing control all invalidate it: keeping it would leave positions marked as unreachable
        // or as "good enough" from a place the player no longer stands in.
        aimTicks.clear();
        aimGiveUp.clear();
        repairRounds.clear();
        stateTolerated.clear();
        pendingPlacementPos = null;
        pendingPlacementWanted = null;
        statusCountdown = 0;
        clearStatus();
    }

    /**
     * Records the builder's plan whenever it changes: which layer it is on, how much work it thinks is left, what it has
     * given up on, and which target it is handing to the pathfinder.
     * <p>
     * A tick is fifty milliseconds, so a per-tick line is a wall of text within a minute and says nothing a reader can
     * use. Only transitions are written now, together with a path calculation failure, and the file stays small enough
     * to actually read after something went wrong.
     */
    private void logTickState(boolean calcFailed, boolean isSafeToCancel) {
        if (!BaritoneLog.enabled()) {
            return;
        }
        tickLogCounter++;
        var pathing = baritone.getPathingBehavior();
        Goal current = pathing == null ? null : pathing.getGoal();
        boolean pathingNow = pathing != null && pathing.isPathing();
        String band = layerScheduler == null ? "<none>" : layerScheduler.toString();
        // The signature has to be stable while nothing meaningful changes. Counting in the failing-position total or the
        // current pathing flag made it flip several times a second and the log turned into the wall of text it replaced.
        String signature = band + "|" + pendingCount() + "|" + abandonedPlacements.size();
        boolean heartbeat = tickLogCounter % 200 == 0;
        if (!heartbeat && !calcFailed && signature.equals(lastPlannedSignature)) {
            return;
        }
        lastPlannedSignature = signature;
        BaritoneLog.log("PLAN",
                "feet=%s layer=%s pending=%s abandoned=%s struggles=%s pathing=%s calcFailStreak=%s calcFailedNow=%s safeCancel=%s ticks=%s placeable=%s inventory=%s -> target %s",
                feetForLog(),
                band,
                pendingCount(),
                abandonedPlacements.size(),
                placementStruggles.size(),
                pathingNow,
                calcFailStreak,
                calcFailed,
                isSafeToCancel,
                ticks,
                approxPlaceable == null ? 0 : approxPlaceable.size(),
                Baritone.settings().allowInventory.value ? "whole inventory" : "hotbar only",
                describeGoal(current));
    }

    /**
     * Stops the build from sitting on a target it cannot actually reach.
     * <p>
     * The builder hands one goal to the pathfinder per tick. When that goal is unreachable the pathfinder simply fails,
     * every tick, forever: nothing throws, nothing is printed, and the only thing that changes the outcome is the player
     * walking somewhere else. This gives up on the blocks behind that goal for the rest of the build, throws away the
     * trimmed pending set so the whole band is looked at again, and lets the normal flow pick new work.
     *
     * @param reason a human readable description of what was stuck, written to the log
     */
    private void escapeStall(String reason) {
        stallRecoveries++;
        BaritoneLog.section("STALL RECOVERY #" + stallRecoveries + " - " + reason);
        BaritoneLog.log("STALL", "feet=%s goal=%s pending=%s targets=%s",
                ctx.playerFeet(), describeGoal(lastGoalSent), pendingCount(), lastGoalTargets);
        int abandoned = 0;
        // A single goal is built from a handful of positions. Abandoning all of them is fine for a pocket, but when the
        // goal came from the whole-band fallback that would throw away everything the band still has to offer, so the
        // cleanup only applies to a reasonably sized target set.
        if (lastGoalTargets.size() <= 64) {
            for (BetterBlockPos pos : lastGoalTargets) {
                if (abandonedPlacements.add(pos)) {
                    abandoned++;
                    BaritoneLog.log("STALL", "  giving up on %s for the rest of this build; resume gives it another try", pos);
                }
            }
        }
        BaritoneLog.log("STALL", "dropped %s positions, discarding the trimmed pending set and rescanning the band", abandoned);
        incorrectPositions = null; // trimmed to a pocket around the player, so it cannot answer "what is left to do"
        fittingPlacementSides.clear(); // a walk failed, not a side; the cached sides are still valid, but the world moved
        wholeBandCooldown = 0;
        calcFailStreak = 0;
        idleTicks = 0;
        lastGoalSent = null;
        lastGoalTargets.clear();
    }

    private boolean recalc(BuilderCalculationContext bcc) {
        if (incorrectPositions == null) {
            incorrectPositions = new HashSet<>();
            fullRecalc(bcc);
            if (incorrectPositions.isEmpty()) {
                BaritoneLog.log("RECALC", "fresh scan of %s found nothing to do", bandDescription());
                return false;
            }
        }
        recalcNearby(bcc);
        if (incorrectPositions.isEmpty()) {
            fullRecalc(bcc);
        }
        return !incorrectPositions.isEmpty();
    }

    private String bandDescription() {
        if (!slicingActive()) {
            return "the whole target";
        }
        return "band " + layerScheduler + " (y " + layerScheduler.sliceMinY() + ".." + layerScheduler.sliceMaxY() + ")";
    }

    private void trim() {
        if (incorrectPositions == null || ctx.player() == null) {
            return;
        }
        HashSet<BetterBlockPos> copy = new HashSet<>(incorrectPositions);
        copy.removeIf(pos -> pos.distSqr(ctx.player().blockPosition()) > 200);
        if (!copy.isEmpty()) {
            incorrectPositions = copy;
        }
    }

    private void recalcNearby(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        int radius = Baritone.settings().builderTickScanRadius.value;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired != null) {
                        // we care about this position
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        if (valid(bcc.bsi.get0(x, y, z), desired, false)) {
                            incorrectPositions.remove(pos);
                            observedCompleted.add(BetterBlockPos.longHash(pos));
                        } else {
                            incorrectPositions.add(pos);
                            observedCompleted.remove(BetterBlockPos.longHash(pos));
                        }
                    }
                }
            }
        }
    }

    private void fullRecalc(BuilderCalculationContext bcc) {
        incorrectPositions = new HashSet<>();
        // a full pass means the world around the target may have changed, so sides that could not produce a wanted
        // orientation before deserve another simulation instead of staying cached as impossible
        fittingPlacementSides.clear();
        // when the build is being sliced, only scan the band that is currently being worked on: blocks outside it
        // are somebody else's turn, and collecting them would make the goal set span the whole target again
        int minY = 0;
        int maxY = schematic.heightY() - 1;
        if (slicingActive()) {
            minY = Math.max(0, layerScheduler.sliceMinY());
            maxY = Math.min(maxY, layerScheduler.sliceMaxY());
        }
        for (int y = minY; y <= maxY; y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + origin.getX();
                    int blockY = y + origin.getY();
                    int blockZ = z + origin.getZ();
                    BlockState current = bcc.bsi.get0(blockX, blockY, blockZ);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    if (bcc.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                        // we can directly observe this block, it is in render distance
                        if (valid(bcc.bsi.get0(blockX, blockY, blockZ), schematic.desiredState(x, y, z, current, this.approxPlaceable), false)) {
                            observedCompleted.add(BetterBlockPos.longHash(blockX, blockY, blockZ));
                        } else {
                            incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                            observedCompleted.remove(BetterBlockPos.longHash(blockX, blockY, blockZ));
                            if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                                return;
                            }
                        }
                        continue;
                    }
                    // this is not in render distance
                    if (!observedCompleted.contains(BetterBlockPos.longHash(blockX, blockY, blockZ))) {
                        // and we've never seen this position be correct
                        // therefore mark as incorrect
                        incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                        if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                            return;
                        }
                    }
                }
            }
        }
    }

    /**
     * @return whether the build is currently being worked through one horizontal layer at a time
     */
    private boolean slicingActive() {
        return layerScheduler != null && Baritone.settings().buildLayerSlicing.value;
    }

    /**
     * Sets up the layer cursor for the schematic that is currently being built.
     */
    private BuilderLayerScheduler createLayerScheduler() {
        if (realSchematic == null) {
            return null;
        }
        int sliceHeight = Math.max(1, Baritone.settings().buildLayerSliceHeight.value);
        // startAtLayer counts layers of layerHeight, which is what the old buildInLayers cursor used as well
        int startAtY = layer * Baritone.settings().layerHeight.value;
        int stopAtY = stopAtHeight > 0 ? stopAtHeight : realSchematic.heightY();
        boolean descending = Baritone.settings().layerOrder.value || isRemovalHeavy(realSchematic);
        return new BuilderLayerScheduler(realSchematic.heightY(), sliceHeight, descending, startAtY, stopAtY);
    }

    /**
     * Decides whether the target is mostly about removing blocks or mostly about placing them.
     * <p>
     * This only chooses the direction of travel, so a bounded sample of the target is enough. Removing from the top
     * down keeps the builder from digging a shaft and dropping into it, placing from the bottom up keeps every block
     * it places supported by the block below it.
     *
     * @return {@code true} when more sampled blocks have to be removed than placed
     */
    private boolean isRemovalHeavy(ISchematic target) {
        if (target == null) {
            return false;
        }
        if (!Baritone.settings().buildLayerAutoOrder.value) {
            return false; // the user asked for layerOrder to make this decision
        }
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        int samples = 8;
        int stepX = Math.max(1, target.widthX() / samples);
        int stepY = Math.max(1, target.heightY() / samples);
        int stepZ = Math.max(1, target.lengthZ() / samples);
        int removals = 0;
        int placements = 0;
        for (int y = 0; y < target.heightY(); y += stepY) {
            for (int z = 0; z < target.lengthZ(); z += stepZ) {
                for (int x = 0; x < target.widthX(); x += stepX) {
                    int blockX = x + origin.getX();
                    int blockY = y + origin.getY();
                    int blockZ = z + origin.getZ();
                    if (!bsi.worldContainsLoadedChunk(blockX, blockZ)) {
                        continue; // we cannot tell what is there, so it does not get a vote
                    }
                    BlockState current = bsi.get0(blockX, blockY, blockZ);
                    if (!target.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    BlockState desired = target.desiredState(x, y, z, current, approxPlaceable);
                    if (desired == null || valid(current, desired, false)) {
                        continue;
                    }
                    boolean currentIsAir = current.getBlock() instanceof AirBlock;
                    boolean desiredIsAir = desired.getBlock() instanceof AirBlock;
                    if (desiredIsAir && !currentIsAir) {
                        removals++;
                    } else if (!desiredIsAir && currentIsAir) {
                        placements++;
                    }
                }
            }
        }
        return removals > placements;
    }

    /**
     * Narrows the pending blocks down to the local pocket around the closest one, so that each path is a short sweep
     * rather than a trip to the far side of the layer.
     * <p>
     * Only the horizontal distance to the builder counts when choosing the pocket, because the goal decision for a
     * block depends on the block straight below it; a pocket that is a horizontal neighbourhood keeps that
     * neighbourhood intact. The vertical ordering inside the layer follows the direction the layer cursor moves in.
     *
     * @return the pending blocks inside the pocket, or all of them when the restriction is disabled
     */
    private Collection<BetterBlockPos> selectSweepPocket() {
        if (incorrectPositions == null || ctx.player() == null) {
            return incorrectPositions;
        }
        int radius = Baritone.settings().buildSliceSweepRadius.value;
        if (radius <= 0 || incorrectPositions.size() <= 1) {
            return incorrectPositions;
        }
        BetterBlockPos playerFeet = ctx.playerFeet();
        boolean descending = layerScheduler == null || layerScheduler.isDescending();
        BetterBlockPos anchor = null;
        int bestHorizontal = Integer.MAX_VALUE;
        int bestVertical = Integer.MAX_VALUE;
        for (BetterBlockPos pos : incorrectPositions) {
            int dx = pos.x - playerFeet.x;
            int dz = pos.z - playerFeet.z;
            int horizontal = dx * dx + dz * dz;
            // ascending works from the bottom of the layer up, descending from the top down
            int vertical = descending ? -pos.y : pos.y;
            boolean better = anchor == null
                    || horizontal < bestHorizontal
                    || (horizontal == bestHorizontal && vertical < bestVertical)
                    || (horizontal == bestHorizontal && vertical == bestVertical
                        && (pos.z < anchor.z || (pos.z == anchor.z && pos.x < anchor.x)));
            if (better) {
                anchor = pos;
                bestHorizontal = horizontal;
                bestVertical = vertical;
            }
        }
        List<BetterBlockPos> pocket = new ArrayList<>();
        for (BetterBlockPos pos : incorrectPositions) {
            int dx = pos.x - anchor.x;
            int dz = pos.z - anchor.z;
            if (dx * dx + dz * dz <= radius * radius) {
                pocket.add(pos);
            }
        }
        return pocket.isEmpty() ? incorrectPositions : pocket;
    }

    /**
     * Restricts the schematic that is being built to the horizontal layer the cursor currently points at.
     */
    private final class LayerSliceSchematic implements ISchematic {

        private final ISchematic delegate;

        private final BuilderLayerScheduler cursor;

        private LayerSliceSchematic(ISchematic delegate, BuilderLayerScheduler cursor) {
            this.delegate = delegate;
            this.cursor = cursor;
        }

        @Override
        public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
            return delegate.desiredState(x, y, z, current, BuilderProcess.this.approxPlaceable);
        }

        @Override
        public boolean inSchematic(int x, int y, int z, BlockState currentState) {
            return ISchematic.super.inSchematic(x, y, z, currentState) && cursor.containsLayer(y) && delegate.inSchematic(x, y, z, currentState);
        }

        @Override
        public void reset() {
            delegate.reset();
        }

        @Override
        public int widthX() {
            return delegate.widthX();
        }

        @Override
        public int heightY() {
            return delegate.heightY();
        }

        @Override
        public int lengthZ() {
            return delegate.lengthZ();
        }
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable) {
        return assemble(bcc, approxPlaceable, false);
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable, boolean logMissing) {
        if (incorrectPositions == null) {
            // escapeStall and onLostControl both drop the set; a tick that is still in flight must not dereference it
            return null;
        }
        return assemble(bcc, approxPlaceable, logMissing, incorrectPositions);
    }

    /**
     * Builds the goal to path towards from a subset of the pending blocks, so that a caller can restrict the builder
     * to the part of the target it is currently standing in.
     *
     * @param candidates the pending blocks that are allowed to contribute a goal, in any order
     */
    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable, boolean logMissing, Collection<BetterBlockPos> candidates) {
        if (candidates == null) {
            return null;
        }
        List<BetterBlockPos> placeable = new ArrayList<>();
        List<BetterBlockPos> breakable = new ArrayList<>();
        List<BetterBlockPos> sourceLiquids = new ArrayList<>();
        List<BetterBlockPos> flowingLiquids = new ArrayList<>();
        Map<BlockState, Integer> missing = new HashMap<>();
        List<BetterBlockPos> outOfBounds = new ArrayList<>();
        candidates.forEach(pos -> {
            if (abandonedPlacements.contains(pos)) {
                return; // already failed to place here for long enough; the next resume gives it another chance
            }
            BlockState state = bcc.bsi.get0(pos);
            if (state.getBlock() instanceof AirBlock) {
                BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, state);
                if (desired == null) {
                    outOfBounds.add(pos);
                } else if (couldPlaceAs(desired, approxPlaceable)) {
                    placeable.add(pos);
                } else {
                    missing.put(desired, 1 + missing.getOrDefault(desired, 0));
                }
            } else {
                if (state.getBlock() instanceof LiquidBlock) {
                    // if the block itself is JUST a liquid (i.e. not just a waterlogged block), we CANNOT break it
                    // TODO for 1.13 make sure that this only matches pure water, not waterlogged blocks
                    if (!MovementHelper.possiblyFlowing(state)) {
                        // if it's a source block then we want to replace it with a throwaway
                        sourceLiquids.add(pos);
                    } else {
                        flowingLiquids.add(pos);
                    }
                } else if (isOurScaffolding(pos, bcc, state)) {
                    // A throwaway block the pathfinder put here for itself while climbing, in a cell the target wants
                    // empty. Digging it out only makes the next path put it back, which is the loop where the builder
                    // and its own path fight over the same block. Cells the target wants filled are NOT skipped: there
                    // the scaffolding is in the way of a real block and has to be removed like any other obstruction.
                } else if (stateTolerated.contains(pos)) {
                    // mined and replaced REPAIR_ROUND_LIMIT times already and the state never changed: the block
                    // there is the most this spot can hold, so it is not an obstruction worth mining again
                } else {
                    breakable.add(pos);
                }
            }
        });
        incorrectPositions.removeAll(outOfBounds);
        // Remember what this goal will be built from. If the pathfinder cannot produce a route to it, these are the
        // positions that have to be given up on, so a stall can be escaped precisely instead of by resetting everything.
        lastGoalTargets.clear();
        for (BetterBlockPos pos : placeable) {
            lastGoalTargets.add(pos);
        }
        for (BetterBlockPos pos : breakable) {
            lastGoalTargets.add(pos);
        }
        for (BetterBlockPos pos : sourceLiquids) {
            lastGoalTargets.add(pos);
        }
        String assembleSummary = String.format("candidates=%s (%s) -> placeable=%s breakable=%s sourceLiquid=%s flowingLiquid=%s missing=%s outOfBounds=%s",
                candidates.size(),
                candidates == incorrectPositions ? "whole pending set" : "pocket",
                placeable.size(), breakable.size(), sourceLiquids.size(), flowingLiquids.size(),
                missing.size(), outOfBounds.size());
        if (!assembleSummary.equals(lastAssembleSummary)) {
            lastAssembleSummary = assembleSummary;
            BaritoneLog.log("ASSEMBLE", "%s", assembleSummary);
        }
        List<Goal> toBreak = new ArrayList<>();
        breakable.forEach(pos -> toBreak.add(breakGoal(pos, bcc)));
        List<Goal> toPlace = new ArrayList<>();
        // "does the block under this one belong to the same column" is asked once per entry, so looking the answer up
        // in a list made assembling the goals quadratic in the size of the slice - on a big band that alone stalled
        // the tick. A set keeps it linear.
        Set<BetterBlockPos> placeableLookup = new HashSet<>(placeable);
        placeable.forEach(pos -> {
            if (!placeableLookup.contains(pos.below()) && !placeableLookup.contains(pos.below(2))) {
                toPlace.add(placementGoal(pos, bcc));
            }
        });
        sourceLiquids.forEach(pos -> toPlace.add(new GoalBlock(pos.above())));

        if (!toPlace.isEmpty()) {
            return new JankyGoalComposite(new GoalComposite(toPlace.toArray(new Goal[0])), new GoalComposite(toBreak.toArray(new Goal[0])));
        }
        if (toBreak.isEmpty()) {
            if (logMissing && !missing.isEmpty()) {
                BaritoneLog.log("MISSING", "the builder thinks it has none of: %s", missing.entrySet().stream()
                        .map(e -> {
                            // The distinction that matters when reading this: if the same Block is in the inventory, the
                            // judgement is wrong (a state that only differs in orientation, which is exactly the bug this
                            // log exists to catch). If it is not, the player really is short of that item.
                            boolean haveBlock = approxPlaceable.stream().anyMatch(candidate -> candidate.getBlock() == e.getKey().getBlock());
                            return String.format("%sx %s (block in inventory: %s)", e.getValue(), e.getKey(), haveBlock);
                        })
                        .collect(Collectors.joining(", ")));
                logDirect("Missing materials for at least:");
                logDirect(missing.entrySet().stream()
                        .map(e -> String.format("%sx %s", e.getValue(), e.getKey()))
                        .collect(Collectors.joining("\n")));
            }
            if (logMissing && !flowingLiquids.isEmpty()) {
                logDirect("Unreplaceable liquids at at least:");
                logDirect(flowingLiquids.stream()
                        .map(p -> String.format("%s %s %s", p.x, p.y, p.z))
                        .collect(Collectors.joining("\n")));
            }
            return null;
        }
        return new GoalComposite(toBreak.toArray(new Goal[0]));
    }

    /**
     * Re-scans the band that is currently being worked on from scratch and tries to assemble a goal out of all of it.
     * <p>
     * {@link #incorrectPositions} is trimmed to a radius around the player on every tick and {@link #selectSweepPocket}
     * narrows it down further, so "nothing to do" is only ever a statement about that small neighbourhood. This check is
     * what decides whether the whole band really has nothing left that the builder could do right now.
     *
     * @return a goal assembled from the whole band, or {@code null} when the band has nothing left to do at all
     */
    private Goal assembleWholeBand(BuilderCalculationContext bcc) {
        if (wholeBandCooldown > 0) {
            // This path rescans the entire band and reassembles every goal in it, which is far too expensive to repeat
            // on every tick when it just came up empty. Letting the layer cursor move on during the cooldown keeps the
            // job flowing without spending the whole tick budget on a scan that is not going to find anything new.
            return null;
        }
        wholeBandCooldown = WHOLE_BAND_RESCAN_TICKS;
        HashSet<BetterBlockPos> nearby = incorrectPositions;
        fullRecalc(bcc);
        Goal goal = assemble(bcc, approxPlaceable, true);
        if (incorrectPositions.isEmpty()) {
            // an empty set is read as "the band is finished" and would move the cursor along without building anything,
            // so keep the set we came in with when the fresh scan found nothing at all
            incorrectPositions = nearby;
        }
        return goal;
    }

    public static class JankyGoalComposite implements Goal {

        private final Goal primary;
        private final Goal fallback;

        public JankyGoalComposite(Goal primary, Goal fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }


        @Override
        public boolean isInGoal(int x, int y, int z) {
            return primary.isInGoal(x, y, z) || fallback.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return primary.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            JankyGoalComposite goal = (JankyGoalComposite) o;
            return Objects.equals(primary, goal.primary)
                    && Objects.equals(fallback, goal.fallback);
        }

        @Override
        public int hashCode() {
            int hash = -1701079641;
            hash = hash * 1196141026 + primary.hashCode();
            hash = hash * -80327868 + fallback.hashCode();
            return hash;
        }

        @Override
        public String toString() {
            return "JankyComposite Primary: " + primary + " Fallback: " + fallback;
        }
    }

    public static class GoalBreak extends GoalGetToBlock {

        public GoalBreak(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            // can't stand right on top of a block, that might not work (what if it's unsupported, can't break then)
            if (y > this.y) {
                return false;
            }
            // but any other adjacent works for breaking, including inside or below
            return super.isInGoal(x, y, z);
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalBreak{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1636324008;
        }
    }

    private Goal placementGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (!(ctx.world().getBlockState(pos).getBlock() instanceof AirBlock)) {  // TODO can this even happen?
            return new GoalPlace(pos);
        }
        boolean allowSameLevel = !(ctx.world().getBlockState(pos.above()).getBlock() instanceof AirBlock);
        BlockState current = ctx.world().getBlockState(pos);
        BlockState desired = bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current);
        if (desired == null || !placementPlausible(pos, desired)) {
            return new GoalPlace(pos);
        }
        Goal fallback = null;
        for (Direction facing : Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP) {
            //noinspection ConstantConditions
            if (!MovementHelper.canPlaceAgainst(ctx, pos.relative(facing))) {
                continue;
            }
            if (fallback == null) {
                fallback = new GoalAdjacent(pos, pos.relative(facing), allowSameLevel);
            }
            // A directional block only comes out with the orientation the schematic asks for when the builder can reach
            // the right side of it. Walking up to whichever side happens to have something to click is what left the
            // builder standing next to a hopper that would not point at its chest, placing nothing for the rest of the
            // session. The side is now picked by asking the block instead of assuming that any side will do.
            if (placementSideFits(pos, facing, desired)) {
                return new GoalAdjacent(pos, pos.relative(facing), allowSameLevel);
            }
        }
        if (placementWarnings.add(pos)) {
            // Every approachable side was simulated and none of them produces this state, so the build will keep asking
            // for it and never get it. Worth saying out loud once, with the state that was asked for.
            BaritoneLog.log("ORIENT", "no reachable side of %s produces %s; known fitting sides=%s, falling back to %s",
                    pos, desired, fittingPlacementSides.get(desired),
                    fallback == null ? "GoalPlace" : fallback.toString());
        }
        return fallback == null ? new GoalPlace(pos) : fallback;
    }

    /**
     * @return whether a builder that reaches the {@code side} of {@code pos} would place {@code desired} there
     * <p>
     * The rule that decides this lives inside the block: a hopper ends up facing away from the player, a piston towards
     * them, a stair takes its shape from its surroundings. It is asked, not duplicated here, and the answer per state is
     * cached because a build repeats the same handful of states thousands of times.
     */
    private boolean placementSideFits(BlockPos pos, Direction side, BlockState desired) {
        if (desired == null || !hasPlacementOrientation(desired)) {
            return true; // nothing about this block depends on where the builder stands
        }
        Set<Direction> fitting = fittingPlacementSides.get(desired);
        if (fitting == null) {
            fitting = computeFittingPlacementSides(pos, desired);
            fittingPlacementSides.put(desired, fitting);
        }
        return fitting.contains(side);
    }

    /**
     * @return every side of a block on which the builder can stand and still place {@code desired}
     */
    private Set<Direction> computeFittingPlacementSides(BlockPos pos, BlockState desired) {
        Set<Direction> fitting = new HashSet<>();
        ItemStack stack = new ItemStack(desired.getBlock().asItem());
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
            return fitting; // not something that can be placed by hand; leave the decision to the placement search
        }
        for (Direction side : Direction.values()) {
            if (side == Direction.UP) {
                continue; // there is nothing to stand on above the block
            }
            if (sideWouldPlace(stack, pos, side, desired)) {
                fitting.add(side);
            }
        }
        return fitting;
    }

    /**
     * @return whether standing on {@code side} of {@code pos} and looking at the block would place {@code desired}
     */
    private boolean sideWouldPlace(ItemStack stack, BlockPos pos, Direction side, BlockState desired) {
        BlockPos placeAgainst = pos.relative(side);
        Direction clickedFace = side.getOpposite();
        Vec3 clickLocation = new Vec3(
                placeAgainst.getX() + 0.5 + clickedFace.getStepX() * 0.5,
                placeAgainst.getY() + 0.5 + clickedFace.getStepY() * 0.5,
                placeAgainst.getZ() + 0.5 + clickedFace.getStepZ() * 0.5);
        Vec3 eyePosition = new Vec3(
                placeAgainst.getX() + 0.5 - clickedFace.getStepX(),
                placeAgainst.getY() + 0.5,
                placeAgainst.getZ() + 0.5 - clickedFace.getStepZ());
        Rotation aim = RotationUtils.calcRotationFromVec3d(eyePosition,
                new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5), ctx.playerRotations());
        BlockHitResult hit = new BlockHitResult(clickLocation, clickedFace, placeAgainst, false);
        BlockPlaceContext context = placeContext(stack, hit);
        for (Rotation rot : placementRotations(aim)) {
            BlockState placed = simulatePlacementState(context, rot);
            if (placed != null && valid(placed, desired, true)) {
                return true;
            }
        }
        return false;
    }

    private Goal breakGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (Baritone.settings().goalBreakFromAbove.value && bcc.bsi.get0(pos.above()).getBlock() instanceof AirBlock && bcc.bsi.get0(pos.above(2)).getBlock() instanceof AirBlock) { // TODO maybe possible without the up(2) check?
            return new JankyGoalComposite(new GoalBreak(pos), new GoalGetToBlock(pos.above()) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    if (y > this.y || (x == this.x && y == this.y && z == this.z)) {
                        return false;
                    }
                    return super.isInGoal(x, y, z);
                }
            });
        }
        return new GoalBreak(pos);
    }

    public static class GoalAdjacent extends GoalGetToBlock {

        private boolean allowSameLevel;
        private BlockPos no;

        public GoalAdjacent(BlockPos pos, BlockPos no, boolean allowSameLevel) {
            super(pos);
            this.no = no;
            this.allowSameLevel = allowSameLevel;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (x == this.x && y == this.y && z == this.z) {
                return false;
            }
            if (x == no.getX() && y == no.getY() && z == no.getZ()) {
                return false;
            }
            if (!allowSameLevel && y == this.y - 1) {
                return false;
            }
            if (y < this.y - 1) {
                return false;
            }
            return super.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (!super.equals(o)) {
                return false;
            }

            GoalAdjacent goal = (GoalAdjacent) o;
            return allowSameLevel == goal.allowSameLevel
                    && Objects.equals(no, goal.no);
        }

        @Override
        public int hashCode() {
            int hash = 806368046;
            hash = hash * 1412661222 + super.hashCode();
            hash = hash * 1730799370 + (int) BetterBlockPos.longHash(no.getX(), no.getY(), no.getZ());
            hash = hash * 260592149 + (allowSameLevel ? -1314802005 : 1565710265);
            return hash;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalAdjacent{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    public static class GoalPlace extends GoalBlock {

        public GoalPlace(BlockPos placeAt) {
            super(placeAt.above());
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1910811835;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalPlace{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    @Override
    public void onLostControl() {
        incorrectPositions = null;
        name = null;
        schematic = null;
        realSchematic = null;
        layerScheduler = null;
        // This used to survive onLostControl. The slicing branch below keys off it, so the next tick tried to build a
        // scheduler for a target that no longer existed and threw a NullPointerException out of isRemovalHeavy.
        stopAtHeight = 0;
        layer = Baritone.settings().startAtLayer.value;
        numRepeats = 0;
        paused = false;
        wholeBandCooldown = 0;
        fittingPlacementSides.clear();
        placementStruggles.clear();
        abandonedPlacements.clear();
        placementWarnings.clear();
        calcFailStreak = 0;
        idleTicks = 0;
        stillTicks = 0;
        lastProgressFeet = null;
        lastGoalSent = null;
        lastGoalTargets.clear();
        observedCompleted = null;
        resetPlacementTracking();
    }

    @Override
    public String displayName0() {
        return paused ? "Builder Paused" : "Building " + name;
    }

    @Override
    public Optional<Integer> getMinLayer() {
        if (slicingActive()) {
            return Optional.of(layerScheduler.sliceMinY());
        }
        if (Baritone.settings().buildInLayers.value) {
            return Optional.of(this.layer);
        }
        return Optional.empty();
    }

    @Override
    public Optional<Integer> getMaxLayer() {
        if (slicingActive()) {
            return Optional.of(layerScheduler.sliceMaxY() + 1);
        }
        if (Baritone.settings().buildInLayers.value) {
            return Optional.of(this.stopAtHeight);
        }
        return Optional.empty();
    }

    /**
     * Rebuilds {@link #approxPlaceable} only when something actually moved in the inventory. Every entry costs a
     * placement state query and the list is consulted by the placement scan and by the pathfinder's placement cost on
     * every tick, so rebuilding it on a tick where the inventory did not change is pure waste.
     */
    private void refreshApproxPlaceable() {
        if (ctx.player() == null) {
            return;
        }
        List<ItemStack> items = ctx.player().getInventory().getNonEquipmentItems();
        List<Item> snapshot = new ArrayList<>(36);
        for (int i = 0; i < 36; i++) {
            snapshot.add(i < items.size() ? items.get(i).getItem() : Items.AIR);
        }
        if (approxPlaceable != null && snapshot.equals(approxPlaceableSnapshot)) {
            return;
        }
        approxPlaceableSnapshot = snapshot;
        approxPlaceable = approxPlaceable(36);
    }

    /**
     * @return the hotbar part of the placeable list, or the whole list when it is shorter than a hotbar
     */
    private static List<BlockState> hotbarOnly(List<BlockState> placeable) {
        if (placeable == null) {
            return Collections.emptyList();
        }
        return placeable.size() <= 9 ? placeable : placeable.subList(0, 9);
    }

    private List<BlockState> approxPlaceable(int size) {
        List<BlockState> result = new ArrayList<>();
        if (ctx.player() == null) {
            return result;
        }
        List<ItemStack> items = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < size; i++) {
            ItemStack stack = i < items.size() ? items.get(i) : ItemStack.EMPTY;
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                result.add(Blocks.AIR.defaultBlockState());
                continue;
            }
            // <toxic cloud>
            BlockState itemState = ((BlockItem) stack.getItem())
                .getBlock()
                .getStateForPlacement(
                    new BlockPlaceContext(
                        new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}
                    )
                );
            if (itemState != null) {
                result.add(itemState);
            } else {
                result.add(Blocks.AIR.defaultBlockState());
            }
            // </toxic cloud>
        }
        return result;
    }

    /**
     * @return whether {@code property} is decided by how a block is placed rather than by what the block is
     * <p>
     * A hard-coded list of {@link Property} instances can never be complete. Every block declares its own instances, so
     * {@code HopperBlock.FACING} is a different object from {@code HorizontalDirectionalBlock.FACING} even though both
     * are a {@code DirectionProperty} called {@code facing}, and a block added by another mod brings its own objects
     * that no list written here could contain. The old instance list therefore classified a hopper aimed at a chest as
     * a state the player cannot carry, and the build paused announcing a missing material the player was holding.
     * Recognising the property by the type of its values, and by its name for the plain enums, covers every block.
     */
    private static boolean isPlacementOrientation(Property<?> property) {
        for (Comparable<?> value : property.getPossibleValues()) {
            if (value instanceof Direction || value instanceof Direction.Axis) {
                return true;
            }
        }
        return ORIENTATION_PROPERTY_NAMES.contains(property.getName());
    }

    /**
     * @return whether {@code state} carries a property that the placement decides, i.e. whether the block can come out
     * in more than one orientation
     */
    private static boolean hasPlacementOrientation(BlockState state) {
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
            if (entry.getKey().getPossibleValues().size() > 1 && isPlacementOrientation(entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    private boolean sameBlockstate(BlockState first, BlockState second) {
        if (first.getBlock() != second.getBlock()) {
            return false;
        }
        boolean ignoreDirection = Baritone.settings().buildIgnoreDirection.value;
        List<String> ignoredProps = Baritone.settings().buildIgnoreProperties.value;
        Set<Property<?>> derived = derivedProperties.get(first.getBlock());
        if (!ignoreDirection && ignoredProps.isEmpty() && (derived == null || derived.isEmpty())) {
            return first.equals(second); // early return if no properties are being ignored
        }
        Map<Property<?>, Comparable<?>> map1 = first.getValues();
        Map<Property<?>, Comparable<?>> map2 = second.getValues();
        for (Property<?> prop : map1.keySet()) {
            if (map1.get(prop) != map2.get(prop)
                    && !(ignoreDirection && isPlacementOrientation(prop))
                    && (derived == null || !derived.contains(prop))
                    && !ignoredProps.contains(prop.getName())) {
                return false;
            }
        }
        return true;
    }

    private boolean containsBlockState(Collection<BlockState> states, BlockState state) {
        for (BlockState testee : states) {
            if (sameBlockstate(testee, state)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return whether something we are carrying could end up being {@code desired}
     * <p>
     * This answers "do I carry this block", which is all a material check can honestly answer. The state an item ends
     * up in is decided while it is placed, by the face that gets clicked and by the side the builder stands on: a
     * hopper pointed at a chest, a log with an axis, a stair with its shape, or anything a mod gives its own property
     * instances. None of those states can exist inside an inventory, so demanding an exact match reported carried
     * blocks as missing materials and paused the build. The placement path re-derives the state from the real hit
     * result in {@link #hasAnyItemThatWouldPlace} and validates it strictly there, so being lenient here cannot place
     * the wrong block: it can only fail to find a spot for it, which the layer scheduler handles without pausing.
     */
    private boolean couldPlaceAs(BlockState desired, List<BlockState> placeable) {
        if (containsBlockState(placeable, desired)) {
            return true;
        }
        for (BlockState candidate : placeable) {
            if (candidate.getBlock() == desired.getBlock()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return whether the item in one inventory slot could end up being {@code desired} once it is placed
     */
    private boolean couldPlaceFrom(BlockState candidate, BlockState desired) {
        return valid(candidate, desired, true) || candidate.getBlock() == desired.getBlock();
    }

    private boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        if (current.getBlock() instanceof LiquidBlock && Baritone.settings().okIfWater.value) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && Baritone.settings().okIfAir.value.contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && Baritone.settings().buildIgnoreBlocks.value.contains(current.getBlock())) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && Baritone.settings().buildIgnoreExisting.value && !itemVerify) {
            return true;
        }
        if (Baritone.settings().buildValidSubstitutes.value.getOrDefault(desired.getBlock(), Collections.emptyList()).contains(current.getBlock()) && !itemVerify) {
            return true;
        }
        if (current.equals(desired)) {
            return true;
        }
        return sameBlockstate(current, desired);
    }

    public class BuilderCalculationContext extends CalculationContext {

        private final List<BlockState> placeable;
        private final ISchematic schematic;
        private final int originX;
        private final int originY;
        private final int originZ;

        public BuilderCalculationContext() {
            super(BuilderProcess.this.baritone, true); // wew lad
            // The pathfinder prices every block it wants to place from this list, and a block that is not in it costs
            // COST_INF unless the builder happens to carry a throwaway block. Judging the whole target against the
            // hotbar alone therefore marked perfectly buildable steps as impossible, and a path with one impossible
            // step is thrown away and recalculated instead of being walked - which is what made long jobs stand still
            // and then report that it had nothing to do. With allowInventory the whole inventory counts, because a
            // block in the main inventory is one inventory move away from being placeable.
            //
            // The list the tick already built is reused rather than rebuilt here: this context is constructed once per
            // tick and every entry costs a placement state query.
            List<BlockState> placeableNow = BuilderProcess.this.approxPlaceable;
            if (placeableNow != null && placeableNow.size() >= 36) {
                this.placeable = Baritone.settings().allowInventory.value ? placeableNow : placeableNow.subList(0, 9);
            } else {
                this.placeable = approxPlaceable(Baritone.settings().allowInventory.value ? 36 : 9);
            }
            this.schematic = BuilderProcess.this.schematic;
            this.originX = origin.getX();
            this.originY = origin.getY();
            this.originZ = origin.getZ();

            this.jumpPenalty += 10;
            this.backtrackCostFavoringCoefficient = 1;
        }

        private BlockState getSchematic(int x, int y, int z, BlockState current) {
            if (schematic == null) {
                // This context snapshots the target when it is built, and the pathfinder keeps asking it for placement
                // costs for the rest of the search. A build that is cancelled mid-tick leaves it holding null.
                return null;
            }
            if (schematic.inSchematic(x - originX, y - originY, z - originZ, current)) {
                return schematic.desiredState(x - originX, y - originY, z - originZ, current, BuilderProcess.this.approxPlaceable);
            } else {
                return null;
            }
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            if (isPossiblyProtected(x, y, z) || !worldBorder.canPlaceAt(x, z)) { // make calculation fail properly if we can't build
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                // TODO this can return true even when allowPlace is off.... is that an issue?
                if (sch.getBlock() instanceof AirBlock) {
                    // we want this to be air, but they're asking if they can place here
                    // this won't be a schematic block, this will be a throwaway
                    return placeBlockCost * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value; // we're going to have to break it eventually
                }
                if (couldPlaceAs(sch, placeable)) {
                    return 0; // thats right we gonna make it FREE to place a block where it should go in a structure
                    // no place block penalty at all 😎
                    // i'm such an idiot that i just tried to copy and paste the epic gamer moment emoji too
                    // get added to unicode when?
                }
                if (!hasThrowaway) {
                    return COST_INF;
                }
                // we want it to be something that we don't have
                // even more of a pain to place something wrong
                return placeBlockCost * 1.5 * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value;
            } else {
                if (hasThrowaway) {
                    return placeBlockCost;
                } else {
                    return COST_INF;
                }
            }
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            if ((!allowBreak && !allowBreakAnyway.contains(current.getBlock())) || isPossiblyProtected(x, y, z)) {
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                if (sch.getBlock() instanceof AirBlock) {
                    // it should be air
                    // regardless of current contents, we can break it
                    return 1;
                }
                // it should be a real block
                // is it already that block?
                if (valid(bsi.get0(x, y, z), sch, false)) {
                    return Baritone.settings().breakCorrectBlockPenaltyMultiplier.value;
                } else {
                    // can break if it's wrong
                    // would be great to return less than 1 here, but that would actually make the cost calculation messed up
                    // since we're breaking a block, if we underestimate the cost, then it'll fail when it really takes the correct amount of time
                    return 1;

                }
                // TODO do blocks in render distace only?
                // TODO allow breaking blocks that we have a tool to harvest and immediately place back?
            } else {
                return 1; // why not lol
            }
        }
    }
}
