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

package baritone.process;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.*;
import baritone.api.process.IMineProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.cache.CachedChunk;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.path.PathExecutor;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneLog;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.stream.Collectors;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

/**
 * Mine blocks of a certain type
 *
 * @author leijurv
 */
public final class MineProcess extends BaritoneProcessHelper implements IMineProcess {

    private BlockOptionalMetaLookup filter;
    private List<BlockPos> knownOreLocations;
    private List<BlockPos> blacklist; // inaccessible
    // A cell the search failed to reach is skipped for a while, not for the rest of the run: a bridge, a mined
    // shaft or simply a different angle can make it reachable again, and hiding it forever is what left ores
    // unhighlighted and never scheduled.
    private final Map<BlockPos, Integer> blacklistedUntil = new HashMap<>();
    private static final int BLACKLIST_TTL_TICKS = 1200;
    // Cells that were within reach but had no visible face from anywhere around them. They are skipped on a
    // cooldown for the same reason: this is a fact about the current stance, not about the ore.
    private final Map<BlockPos, Integer> noSightCooldown = new HashMap<>();
    private static final int NO_SIGHT_COOLDOWN_TICKS = 600;
    private int lastTrackedLogged = -1;
    private Map<BlockPos, Long> anticipatedDrops;
    private BlockPos branchPoint;
    private GoalRunAway branchPointRunaway;
    private int desiredQuantity;
    private int tickCount;

    /**
     * Building the composite is the expensive half of {@link #updateGoal()}: it runs every pruned position
     * through coalesce, which reads block states. The result only changes when those positions do, so it is
     * kept between ticks and rebuilt when the pruned list or the blacklist moves.
     */
    private Goal goalCache;
    private long goalCacheKey;
    private int goalCacheTick;

    private PathingCommand lastCommand;
    private int lastCommandTick;

    /**
     * Positions around a failed target that are dropped together with it. A search that cannot reach one ore in a
     * pile cannot reach its neighbours either, and every attempt costs a full failure timeout before the tick
     * resumes, which is the pause that was showing up as a two second freeze at block edges.
     */
    private static final double UNREACHABLE_CLUSTER_SQR = 9.0D;

    /**
     * Cheap prefilter for candidates worth a line of sight check. The real limit is the block reach distance; this
     * only keeps that raycast off all sixty four known positions on every tick.
     */
    private static final double IN_REACH_SQR = 25.0D;
    // The block being mined is kept across ticks on purpose. Two equally distant ores used to trade places every
    // tick, which retargeted the look every tick, and a look that never settles never opens a click.
    private BlockPos breakTarget;
    // A cell that has line of sight to a target no face of which is reachable from where the player stands.
    private BlockPos approachStance;
    private BlockPos approachFor;
    private int approachAtTick;
    private static final int APPROACH_CACHE_TICKS = 40;
    private static final double EYE_HEIGHT = 1.62D;
    // Ticks spent on a target with no visible face anywhere around it, so an impossible target is dropped instead of
    // being waited on forever.
    private final Map<BlockPos, Integer> noSightTicks = new HashMap<>();
    private static final int NO_SIGHT_LIMIT = 60;
    /** A goal rebuild that takes longer than this is worth a line in the debug log. */
    private static final long SLOW_GOAL_UPDATE_MS = 20L;

    /**
     * updateGoal runs every mineGoalUpdateInterval ticks, so this covers roughly twenty ticks of reuse
     * before the composite is rebuilt regardless, in case something outside the key changed.
     */
    private static final int GOAL_CACHE_TICKS = 4;

    public MineProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        return filter != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (desiredQuantity > 0) {
            int curr = ctx.player().getInventory().getNonEquipmentItems().stream()
                    .filter(stack -> filter.has(stack))
                    .mapToInt(ItemStack::getCount).sum();
            if (curr >= desiredQuantity) {
                logDirect("Have " + curr + " valid items");
                cancel();
                return null;
            }
        }
        if (calcFailed) {
            if (!knownOreLocations.isEmpty() && Baritone.settings().blacklistClosestOnFailure.value) {
                logDirect("Unable to find any path to " + filter + ", blacklisting presumably unreachable closest instance...");
                if (Baritone.settings().notificationOnMineFail.value) {
                    logNotification("Unable to find any path to " + filter + ", blacklisting presumably unreachable closest instance...", true);
                }
                BlockPos closest = knownOreLocations.stream().min(Comparator.comparingDouble(ctx.playerFeet()::distSqr)).orElse(null);
                if (closest != null) {
                    List<BlockPos> cluster = knownOreLocations.stream()
                            .filter(pos -> pos.distSqr(closest) <= UNREACHABLE_CLUSTER_SQR)
                            .collect(Collectors.toList());
                    int until = tickCount + BLACKLIST_TTL_TICKS;
                    cluster.forEach(pos -> blacklistedUntil.put(pos, until));
                    blacklist.addAll(cluster);
                    knownOreLocations.removeIf(blacklist::contains);
                    BaritoneLog.log("MINE", "no path to %s, blacklisting %s position(s) around it instead of one, so the next search does not spend another failure timeout on the same pile", closest, cluster.size());
                }
            } else {
                logDirect("Unable to find any path to " + filter + ", canceling mine");
                if (Baritone.settings().notificationOnMineFail.value) {
                    logNotification("Unable to find any path to " + filter + ", canceling mine", true);
                }
                cancel();
                return null;
            }
        }

        if (!blacklistedUntil.isEmpty()) {
            int now = tickCount;
            blacklist.removeIf(pos -> blacklistedUntil.getOrDefault(pos, 0) <= now);
            blacklistedUntil.keySet().retainAll(blacklist);
        }
        updateLoucaSystem();
        int mineGoalUpdateInterval = Baritone.settings().mineGoalUpdateInterval.value;
        List<BlockPos> curr = new ArrayList<>(knownOreLocations);
        if (mineGoalUpdateInterval != 0 && tickCount++ % mineGoalUpdateInterval == 0) { // big brain
            CalculationContext context = new CalculationContext(baritone, true);
            Baritone.getExecutor().execute(() -> rescan(curr, context));
        }
        if (Baritone.settings().legitMine.value) {
            if (!addNearby()) {
                cancel();
                return null;
            }
        }
        // Pathing only breaks blocks it walks through, so a target that already counts as reached from where the
        // player stands is never touched: standing under an overhead ore is exactly that case, and the old code only
        // handled targets in the player's own column. Break whatever is in reach from here, in any direction.
        BlockPos feetNow = ctx.playerFeet();
        baritone.getInputOverrideHandler().clearAllKeys();
        BlockPos target = chooseBreakTarget(curr, feetNow);
        if (target != null && ctx.player().onGround() && isSafeToCancel) {
            BlockState state = baritone.bsi.get0(target);
            if (!MovementHelper.avoidBreaking(baritone.bsi, target.getX(), target.getY(), target.getZ(), state)) {
                Optional<Rotation> rot = RotationUtils.reachable(ctx, target);
                if (rot.isPresent()) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(target));
                    if (ctx.isLookingAt(target) || ctx.playerRotations().isReallyCloseTo(rot.get())) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                // Every face is covered from where the player stands, so no look angle reaches the block, and the
                // goal that covers it already counts as satisfied: that combination is what made mine sit still
                // until the player moved. Walk to a cell with a clean shot at one of its faces instead.
                BlockPos stance = stanceLookingAt(target);
                if (stance != null && !stance.equals(feetNow)) {
                    BaritoneLog.log("MINE", "no face of %s at %s is visible from %s, walking to %s to get a clean shot", state, target, feetNow, stance);
                    return new PathingCommand(new GoalBlock(stance), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
                }
                int noSight = noSightTicks.merge(target, 1, Integer::sum);
                if (stance == null && noSight >= NO_SIGHT_LIMIT) {
                    noSightTicks.remove(target);
                    breakTarget = null;
                    approachStance = null;
                    approachFor = null;
                    noSightCooldown.put(target, tickCount + NO_SIGHT_COOLDOWN_TICKS);
                    BaritoneLog.log("MINE", "no cell around %s at %s can see a face of it right now, so it is skipped for %s ticks instead of being dropped", state, target, NO_SIGHT_COOLDOWN_TICKS);
                }
            }
        }
        int updateInterval = Math.max(1, Baritone.settings().mineGoalUpdateInterval.value);
        PathingCommand command;
        if (lastCommand == null || tickCount - lastCommandTick >= updateInterval) {
            long startedAt = System.nanoTime();
            command = updateGoal();
            lastCommandTick = tickCount;
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            if (elapsedMs >= SLOW_GOAL_UPDATE_MS) {
                BaritoneLog.log("MINE", "goal update took %sms over %s known position(s)", elapsedMs, knownOreLocations == null ? 0 : knownOreLocations.size());
            }
            lastCommand = command;
        } else {
            command = lastCommand;
        }
        if (command == null) {
            // none in range
            // maybe say something in chat? (ahem impact)
            cancel();
            return null;
        }
        return command;
    }


    private void updateLoucaSystem() {
        Map<BlockPos, Long> copy = new HashMap<>(anticipatedDrops);
        ctx.getSelectedBlock().ifPresent(pos -> {
            if (knownOreLocations.contains(pos)) {
                copy.put(pos, System.currentTimeMillis() + Baritone.settings().mineDropLoiterDurationMSThanksLouca.value);
            }
        });
        // elaborate dance to avoid concurrentmodificationexcepption since rescan thread reads this
        // don't want to slow everything down with a gross lock do we now
        for (BlockPos pos : anticipatedDrops.keySet()) {
            if (copy.get(pos) < System.currentTimeMillis()) {
                copy.remove(pos);
            }
        }
        anticipatedDrops = copy;
    }

    @Override
    public void onLostControl() {
        breakTarget = null;
        approachStance = null;
        approachFor = null;
        noSightTicks.clear();
        noSightCooldown.clear();
        blacklistedUntil.clear();
        mine(0, (BlockOptionalMetaLookup) null);
    }

    @Override
    public String displayName0() {
        return "Mine " + filter;
    }

    /**
     * The block currently being mined. Kept across ticks so the aim has a chance to settle: picking the nearest
     * target every tick flips between equally distant ores, and a look target that changes every tick never lines up.
     */
    private BlockPos chooseBreakTarget(List<BlockPos> curr, BlockPos feet) {
        if (breakTarget != null) {
            boolean usable = !(BlockStateInterface.get(ctx, breakTarget).getBlock() instanceof AirBlock)
                    && breakTarget.distSqr(feet) <= IN_REACH_SQR
                    && curr.contains(breakTarget)
                    && noSightCooldown.getOrDefault(breakTarget, 0) <= tickCount;
            if (usable) {
                return breakTarget;
            }
            noSightTicks.remove(breakTarget);
            breakTarget = null;
        }
        BlockPos best = curr.stream()
                .filter(pos -> !(BlockStateInterface.get(ctx, pos).getBlock() instanceof AirBlock)) // after breaking a block, it takes mineGoalUpdateInterval ticks for it to actually update this list =(
                .filter(pos -> pos.distSqr(feet) <= IN_REACH_SQR)
                .filter(pos -> noSightCooldown.getOrDefault(pos, 0) <= tickCount)
                .min(Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(feet)).thenComparingLong(BlockPos::asLong))
                .orElse(null);
        if (best != null) {
            breakTarget = best;
            BaritoneLog.log("MINE", "breaking %s at %s from %s%s", BlockStateInterface.get(ctx, best).getBlock(), best, feet,
                    best.distSqr(feet) > 4 ? ": within reach, but off the path the search would take" : "");
        }
        return best;
    }

    /**
     * A cell from which at least one face of {@code target} is exposed. A block whose every reachable face is covered
     * cannot be hit from any angle at the current position, so the way to mine it is to stand somewhere else.
     */
    private BlockPos stanceLookingAt(BlockPos target) {
        if (approachFor != null && approachFor.equals(target) && approachStance != null && tickCount - approachAtTick < APPROACH_CACHE_TICKS) {
            return approachStance;
        }
        approachFor = target;
        approachAtTick = tickCount;
        approachStance = null;
        double reach = ctx.playerController().getBlockReachDistance();
        Vec3 center = new Vec3(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D);
        List<BlockPos> candidates = new ArrayList<>(12);
        for (Direction side : Direction.values()) {
            BlockPos adjacent = target.relative(side);
            if (canStandAt(adjacent)) {
                candidates.add(adjacent);
            }
            BlockPos belowAdjacent = adjacent.below();
            if (canStandAt(belowAdjacent)) {
                candidates.add(belowAdjacent);
            }
        }
        for (BlockPos candidate : candidates) {
            Vec3 eyes = new Vec3(candidate.getX() + 0.5D, candidate.getY() + EYE_HEIGHT, candidate.getZ() + 0.5D);
            for (Direction face : Direction.values()) {
                Vec3 point = center.add(new Vec3(face.getStepX(), face.getStepY(), face.getStepZ()).scale(0.5001D));
                if (eyes.distanceTo(point) > reach) {
                    continue;
                }
                BlockHitResult hit = ctx.world().clip(new ClipContext(eyes, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, ctx.player()));
                if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) {
                    approachStance = candidate;
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean canStandAt(BlockPos pos) {
        if (!(ctx.world().getBlockState(pos).getBlock() instanceof AirBlock)) {
            return false;
        }
        if (!(ctx.world().getBlockState(pos.above()).getBlock() instanceof AirBlock)) {
            return false;
        }
        return !ctx.world().getBlockState(pos.below()).getCollisionShape(ctx.world(), pos.below()).isEmpty();
    }

    private Goal goalFor(List<BlockPos> locs, List<BlockPos> dropped, CalculationContext context) {
        // A composite with hundreds of members widens the frontier and slows every recalculation down, so only
        // the closest few become goals. The rest stay in knownOreLocations, where they still show up as targets
        // and come up again as soon as the near ones are gone.
        int limit = Math.max(1, Baritone.settings().mineMaxOreLocationsCount.value);
        List<BlockPos> near = locs.size() > limit ? locs.subList(0, limit) : locs;
        long key = 1125899906842597L;
        for (BlockPos pos : near) {
            key = key * 31 + pos.asLong();
        }
        key = key * 31 + blacklist.size();
        key = key * 31 + dropped.size();
        key = key * 31 + limit;
        if (goalCache == null || key != goalCacheKey || tickCount - goalCacheTick >= GOAL_CACHE_TICKS) {
            goalCache = new GoalComposite(near.stream().map(loc -> coalesce(loc, near, context)).toArray(Goal[]::new));
            goalCacheKey = key;
            goalCacheTick = tickCount;
        }
        return goalCache;
    }

    private PathingCommand updateGoal() {
        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return null;
        }

        boolean legit = Baritone.settings().legitMine.value;
        List<BlockPos> locs = knownOreLocations;
        if (!locs.isEmpty()) {
            CalculationContext context = new CalculationContext(baritone);
            List<BlockPos> dropped = droppedItemsScan();
            List<BlockPos> locs2 = prune(context, new ArrayList<>(locs), filter, Baritone.settings().mineMaxOreLocationsCount.value, blacklist, dropped);
            // A mined cell stays in the list while its drop lies there, and a composite of goals reads as reached as
            // soon as any single member is reached. Keeping a cell that no longer holds the ore therefore makes the
            // whole objective look finished while untouched ores wait, which is the idle loop where mine stands under
            // a diamond and never swings. Only positions that still hold the target may build goals; drops keep being
            // tracked separately for the loiter logic.
            int candidates = locs2.size();
            locs2.removeIf(pos -> context.bsi.isLoaded(pos.getX(), pos.getZ()) && !filter.has(context.bsi.get0(pos)));
            int stale = candidates - locs2.size();
            if (stale > 0) {
                // Logging this every prune flooded the log four times a second with the same sentence, and building
                // that sentence stringifies the whole filter each time.
                BaritoneLog.log("MINE", "dropped %s stale target position(s): those cells no longer hold %s, and a goal there would count as reached", stale, filter);
            }
            // can't reassign locs, gotta make a new var locs2, because we use it in a lambda right here, and variables you use in a lambda must be effectively final
            knownOreLocations = locs2;
            if (locs2.size() != lastTrackedLogged) {
                // One line whenever the tracked set changes size: it makes it obvious whether every ore in view is
                // actually being tracked, which is what the highlight boxes are drawn from.
                lastTrackedLogged = locs2.size();
                BaritoneLog.log("MINE", "tracking %s target position(s), the closest %s of them are goals right now", locs2.size(), Math.min(locs2.size(), Baritone.settings().mineMaxOreLocationsCount.value));
            }
            if (!locs2.isEmpty()) {
                Goal goal = goalFor(locs2, dropped, context);
                PathingCommandType type = legit ? PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH : PathingCommandType.REVALIDATE_GOAL_AND_PATH;
                return new PathingCommand(goal, type);
            }
            // every known position turned out to be stale, so there is nothing to dig here; fall through to the
            // explore branch instead of handing the pathfinder an empty composite, which it can never satisfy
            BaritoneLog.log("MINE", "no target position is left to dig, falling back to exploring for more");
        }
        // we don't know any ore locations at the moment
        if (!legit && !Baritone.settings().exploreForBlocks.value) {
            return null;
        }
        // only when we should explore for blocks or are in legit mode we do this
        int y = Baritone.settings().legitMineYLevel.value;
        if (branchPoint == null) {
            /*if (!baritone.getPathingBehavior().isPathing() && playerFeet().y == y) {
                // cool, path is over and we are at desired y
                branchPoint = playerFeet();
                branchPointRunaway = null;
            } else {
                return new GoalYLevel(y);
            }*/
            branchPoint = ctx.playerFeet();
        }
        // TODO shaft mode, mine 1x1 shafts to either side
        // TODO also, see if the GoalRunAway with maintain Y at 11 works even from the surface
        if (branchPointRunaway == null) {
            branchPointRunaway = new GoalRunAway(1, y, branchPoint) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    return false;
                }

                @Override
                public double heuristic() {
                    return Double.NEGATIVE_INFINITY;
                }
            };
        }
        return new PathingCommand(branchPointRunaway, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private void rescan(List<BlockPos> already, CalculationContext context) {
        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return;
        }
        if (Baritone.settings().legitMine.value) {
            return;
        }
        List<BlockPos> dropped = droppedItemsScan();
        List<BlockPos> locs = searchWorld(context, filter, Baritone.settings().mineMaxOreLocationsCount.value, already, blacklist, dropped);
        locs.addAll(dropped);
        if (locs.isEmpty() && Baritone.settings().allowOnlyExposedOres.value) {
            // everything worth mining is behind a cover layer, which is normal in a structure (an ore under a
            // decorative block, a vein sealed by anything solid). Clearing the cover is part of the job, so look
            // again without the line-of-sight filter before treating the target as gone.
            locs = searchWorld(context, filter, Baritone.settings().mineMaxOreLocationsCount.value, already, blacklist, dropped, false);
            locs.addAll(dropped);
            if (!locs.isEmpty()) {
                logDirect("The target is behind a cover, digging to it instead of giving up");
                BaritoneLog.log("MINE", "exposure filter left nothing, %s buried target(s) recovered: %s", locs.size(), locs.get(0));
            }
        }
        if (locs.isEmpty() && !Baritone.settings().exploreForBlocks.value) {
            logDirect("No locations for " + filter + " known, cancelling");
            if (Baritone.settings().notificationOnMineFail.value) {
                logNotification("No locations for " + filter + " known, cancelling", true);
            }
            cancel();
            return;
        }
        knownOreLocations = locs;
    }

    private boolean internalMiningGoal(BlockPos pos, CalculationContext context, List<BlockPos> locs) {
        // Here, BlockStateInterface is used because the position may be in a cached chunk (the targeted block is one that is kept track of)
        if (locs.contains(pos)) {
            return true;
        }
        BlockState state = context.bsi.get0(pos);
        if (Baritone.settings().internalMiningAirException.value && state.getBlock() instanceof AirBlock) {
            return true;
        }
        return filter.has(state) && plausibleToBreak(context, pos);
    }

    private Goal coalesce(BlockPos loc, List<BlockPos> locs, CalculationContext context) {
        boolean assumeVerticalShaftMine = !(baritone.bsi.get0(loc.above()).getBlock() instanceof FallingBlock);
        if (!Baritone.settings().forceInternalMining.value) {
            if (assumeVerticalShaftMine) {
                // we can get directly below the block
                return new GoalThreeBlocks(loc);
            } else {
                // we need to get feet or head into the block
                return new GoalTwoBlocks(loc);
            }
        }
        boolean upwardGoal = internalMiningGoal(loc.above(), context, locs);
        boolean downwardGoal = internalMiningGoal(loc.below(), context, locs);
        boolean doubleDownwardGoal = internalMiningGoal(loc.below(2), context, locs);
        if (upwardGoal == downwardGoal) { // symmetric
            if (doubleDownwardGoal && assumeVerticalShaftMine) {
                // we have a checkerboard like pattern
                // this one, and the one two below it
                // therefore it's fine to path to immediately below this one, since your feet will be in the doubleDownwardGoal
                // but only if assumeVerticalShaftMine
                return new GoalThreeBlocks(loc);
            } else {
                // this block has nothing interesting two below, but is symmetric vertically so we can get either feet or head into it
                return new GoalTwoBlocks(loc);
            }
        }
        if (upwardGoal) {
            // downwardGoal known to be false
            // ignore the gap then potential doubleDownward, because we want to path feet into this one and head into upwardGoal
            return new GoalBlock(loc);
        }
        // upwardGoal known to be false, downwardGoal known to be true
        if (doubleDownwardGoal && assumeVerticalShaftMine) {
            // this block and two below it are goals
            // path into the center of the one below, because that includes directly below this one
            return new GoalTwoBlocks(loc.below());
        }
        // upwardGoal false, downwardGoal true, doubleDownwardGoal false
        // just this block and the one immediately below, no others
        return new GoalBlock(loc.below());
    }

    private static class GoalThreeBlocks extends GoalTwoBlocks {

        public GoalThreeBlocks(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return x == this.x && (y == this.y || y == this.y - 1 || y == this.y - 2) && z == this.z;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            int xDiff = x - this.x;
            int yDiff = y - this.y;
            int zDiff = z - this.z;
            return GoalBlock.calculate(xDiff, yDiff < -1 ? yDiff + 2 : yDiff == -1 ? 0 : yDiff, zDiff);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 393857768;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalThreeBlocks{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    public List<BlockPos> droppedItemsScan() {
        if (!Baritone.settings().mineScanDroppedItems.value) {
            return Collections.emptyList();
        }
        List<BlockPos> ret = new ArrayList<>();
        for (Entity entity : ((ClientLevel) ctx.world()).entitiesForRendering()) {
            if (entity instanceof ItemEntity) {
                ItemEntity ei = (ItemEntity) entity;
                if (filter.has(ei.getItem())) {
                    ret.add(entity.blockPosition());
                }
            }
        }
        ret.addAll(anticipatedDrops.keySet());
        return ret;
    }

    public static List<BlockPos> searchWorld(CalculationContext ctx, BlockOptionalMetaLookup filter, int max, List<BlockPos> alreadyKnown, List<BlockPos> blacklist, List<BlockPos> dropped) {
        return searchWorld(ctx, filter, max, alreadyKnown, blacklist, dropped, Baritone.settings().allowOnlyExposedOres.value);
    }

    /**
     * @param requireExposed whether to drop targets that are not next to transparent blocks
     * <p>
     * The exposure filter exists so the player does not dig through a mountain to reach something buried. It is also
     * the reason mining gave up on targets that only look buried: an ore under one layer of cover is not next to air
     * anywhere, so it was filtered out, the location list came back empty and the whole job was cancelled. Reading
     * "no reachable location" out of a list that was filtered by line of sight is not the same as "there is nothing
     * left to mine", so the caller retries with this flag off before concluding that.
     */
    public static List<BlockPos> searchWorld(CalculationContext ctx, BlockOptionalMetaLookup filter, int max, List<BlockPos> alreadyKnown, List<BlockPos> blacklist, List<BlockPos> dropped, boolean requireExposed) {
        List<BlockPos> locs = new ArrayList<>();
        List<Block> untracked = new ArrayList<>();
        for (BlockOptionalMeta bom : filter.blocks()) {
            Block block = bom.getBlock();
            if (CachedChunk.BLOCKS_TO_KEEP_TRACK_OF.contains(block)) {
                BetterBlockPos pf = ctx.baritone.getPlayerContext().playerFeet();

                // maxRegionDistanceSq 2 means adjacent directly or adjacent diagonally; nothing further than that
                locs.addAll(ctx.worldData.getCachedWorld().getLocationsOf(
                        BlockUtils.blockToString(block),
                        Baritone.settings().maxCachedWorldScanCount.value,
                        pf.x,
                        pf.z,
                        2
                ));
            } else {
                untracked.add(block);
            }
        }

        locs = prune(ctx, locs, filter, max, blacklist, dropped, requireExposed);

        if (!untracked.isEmpty() || (Baritone.settings().extendCacheOnThreshold.value && locs.size() < max)) {
            locs.addAll(BaritoneAPI.getProvider().getWorldScanner().scanChunkRadius(
                    ctx.getBaritone().getPlayerContext(),
                    filter,
                    max,
                    10,
                    32
            )); // maxSearchRadius is NOT sq
        }

        locs.addAll(alreadyKnown);

        return prune(ctx, locs, filter, max, blacklist, dropped, requireExposed);
    }

    private boolean addNearby() {
        List<BlockPos> dropped = droppedItemsScan();
        knownOreLocations.addAll(dropped);
        BlockPos playerFeet = ctx.playerFeet();
        BlockStateInterface bsi = new BlockStateInterface(ctx);


        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return false;
        }

        int searchDist = 10;
        double fakedBlockReachDistance = 20; // at least 10 * sqrt(3) with some extra space to account for positioning within the block
        for (int x = playerFeet.getX() - searchDist; x <= playerFeet.getX() + searchDist; x++) {
            for (int y = playerFeet.getY() - searchDist; y <= playerFeet.getY() + searchDist; y++) {
                for (int z = playerFeet.getZ() - searchDist; z <= playerFeet.getZ() + searchDist; z++) {
                    // crucial to only add blocks we can see because otherwise this
                    // is an x-ray and it'll get caught
                    if (filter.has(bsi.get0(x, y, z))) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if ((Baritone.settings().legitMineIncludeDiagonals.value && knownOreLocations.stream().anyMatch(ore -> ore.distSqr(pos) <= 2 /* sq means this is pytha dist <= sqrt(2) */)) || RotationUtils.reachable(ctx, pos, fakedBlockReachDistance).isPresent()) {
                            knownOreLocations.add(pos);
                        }
                    }
                }
            }
        }
        knownOreLocations = prune(new CalculationContext(baritone), knownOreLocations, filter, Baritone.settings().mineMaxOreLocationsCount.value, blacklist, dropped);
        return true;
    }

    private static List<BlockPos> prune(CalculationContext ctx, List<BlockPos> locs2, BlockOptionalMetaLookup filter, int max, List<BlockPos> blacklist, List<BlockPos> dropped) {
        return prune(ctx, locs2, filter, max, blacklist, dropped, Baritone.settings().allowOnlyExposedOres.value);
    }

    private static List<BlockPos> prune(CalculationContext ctx, List<BlockPos> locs2, BlockOptionalMetaLookup filter, int max, List<BlockPos> blacklist, List<BlockPos> dropped, boolean requireExposed) {
        dropped.removeIf(drop -> {
            for (BlockPos pos : locs2) {
                if (pos.distSqr(drop) <= 9 && filter.has(ctx.get(pos.getX(), pos.getY(), pos.getZ())) && MineProcess.plausibleToBreak(ctx, pos)) { // TODO maybe drop also has to be supported? no lava below?
                    return true;
                }
            }
            return false;
        });
        List<BlockPos> locs = locs2
                .stream()
                .distinct()

                // remove any that are within loaded chunks that aren't actually what we want
                .filter(pos -> !ctx.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ()) || filter.has(ctx.get(pos.getX(), pos.getY(), pos.getZ())) || dropped.contains(pos))

                // remove any that are implausible to mine (encased in bedrock, or touching lava)
                .filter(pos -> MineProcess.plausibleToBreak(ctx, pos))

                .filter(pos -> {
                    if (requireExposed) {
                        return isNextToAir(ctx, pos);
                    } else {
                        return true;
                    }
                })

                .filter(pos -> pos.getY() >= Baritone.settings().minYLevelWhileMining.value + ctx.world.dimensionType().minY())

                .filter(pos -> pos.getY() <= Baritone.settings().maxYLevelWhileMining.value)

                .filter(pos -> !blacklist.contains(pos))

                .sorted(Comparator.comparingDouble(ctx.getBaritone().getPlayerContext().player().blockPosition()::distSqr))
                .collect(Collectors.toList());

        // Deliberately not truncated here. max is a budget for how much one scan pass collects, but trimming
        // the result is what made ores disappear: anything past the closest N cells never entered
        // knownOreLocations, so it was never highlighted and never scheduled, however long the run went on.
        // goalFor() limits how many of them become goals instead, which keeps the search frontier small
        // without losing targets.
        return locs;
    }

    public static boolean isNextToAir(CalculationContext ctx, BlockPos pos) {
        int radius = Baritone.settings().allowOnlyExposedOresDistance.value;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > radius) {
                        continue;
                    }
                    BlockPos at = pos.offset(dx, dy, dz);
                    BlockState state = ctx.get(at);
                    // air, water and lava are open by definition; a partial block such as a sculk vein, a rail or a
                    // torch does not hide anything either, so an ore under one of those is still minable
                    if (MovementHelper.isTransparent(state.getBlock()) || MovementHelper.isLowProfileCover(state, ctx.world, at)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    public static boolean plausibleToBreak(CalculationContext ctx, BlockPos pos) {
        BlockState state = ctx.bsi.get0(pos);
        if (MovementHelper.getMiningDurationTicks(ctx, pos.getX(), pos.getY(), pos.getZ(), state, true) >= COST_INF) {
            return false;
        }
        if (MovementHelper.avoidBreaking(ctx.bsi, pos.getX(), pos.getY(), pos.getZ(), state)) {
            return false;
        }

        // bedrock above and below makes it implausible, otherwise we're good
        return !(ctx.bsi.get0(pos.above()).getBlock() == Blocks.BEDROCK && ctx.bsi.get0(pos.below()).getBlock() == Blocks.BEDROCK);
    }

    @Override
    public void mineByName(int quantity, String... blocks) {
        mine(quantity, new BlockOptionalMetaLookup(blocks));
    }

    @Override
    public void mine(int quantity, BlockOptionalMetaLookup filter) {
        this.filter = filter;
        if (this.filterFilter() == null) {
            this.filter = null;
        }
        this.desiredQuantity = quantity;
        this.knownOreLocations = new ArrayList<>();
        this.blacklist = new ArrayList<>();
        this.blacklistedUntil.clear();
        this.noSightCooldown.clear();
        this.lastTrackedLogged = -1;
        this.branchPoint = null;
        this.branchPointRunaway = null;
        this.anticipatedDrops = new HashMap<>();
        if (filter != null) {
            rescan(new ArrayList<>(), new CalculationContext(baritone));
        }
    }

    private BlockOptionalMetaLookup filterFilter() {
        if (this.filter == null) {
            return null;
        }
        if (!Baritone.settings().allowBreak.value) {
            BlockOptionalMetaLookup f = new BlockOptionalMetaLookup(this.filter.blocks()
                    .stream()
                    .filter(e -> Baritone.settings().allowBreakAnyway.value.contains(e.getBlock()))
                    .toArray(BlockOptionalMeta[]::new));
            if (f.blocks().isEmpty()) {
                logDirect("Unable to mine when allowBreak is false and target block is not in allowBreakAnyway!");
                return null;
            }
            return f;
        }
        return filter;
    }
}
