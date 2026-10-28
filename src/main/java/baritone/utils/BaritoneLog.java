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

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * A detailed, human readable trace of everything Baritone decides and does.
 * <p>
 * It is off by default; {@code #debug true} in chat turns it on and {@code #debug false} turns it off again. While it is
 * on, every line Baritone would print to chat is mirrored here, plus a much more verbose stream of internal decisions:
 * which slice of a litematica build is being worked on, what the builder thinks is missing or unreachable, why a path
 * calculation failed, which block it is trying to place and where it is standing while doing so.
 * <p>
 * The file is {@code baritonelog.txt} inside the save directory of the world that is currently loaded (for a multiplayer
 * server it falls back to the run directory of the game, since there is no save folder to write into). Each JVM run
 * truncates the file once, then appends, so a long session produces exactly one log.
 * <p>
 * Writing must never break the game, so every single disk access is wrapped and swallowed. A broken log is worthless,
 * but a crash caused by the log is worse.
 */
public final class BaritoneLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static PrintStream out;
    private static Path file;
    private static boolean truncated;
    private static long written;
    /**
     * How much this session may write before the log stops itself.
     * <p>
     * An eleven minute build once produced thirteen megabytes of the same two lines repeated, which is worse than no log
     * at all: it cannot be read, and it costs the player disk and a stalled editor. The cap makes the file stay small
     * enough to open while still keeping every decision that led up to a problem.
     */
    private static final long MAX_BYTES = 8_000_000L;
    private static long bytesWritten;
    private static boolean capped;
    /** whether {@link #file} is the file inside the save directory, rather than the fallback in the game directory */
    private static boolean worldBound;

    static {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(BaritoneLog::close, "baritone-log-close"));
        } catch (Throwable ignored) {
        }
    }

    private BaritoneLog() {}

    /**
     * @return whether the user asked for a debug log with {@code #debug true}
     */
    public static boolean enabled() {
        try {
            return BaritoneAPI.getSettings().debug.value;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * @return the file being written to, or null if nothing has been written yet
     */
    public static Path file() {
        ensureOpen();
        return file;
    }

    public static long writtenLines() {
        return written;
    }

    /**
     * Mirrors a message that went to chat, so the log has the exact same narrative the user saw.
     */
    public static void chat(String message) {
        if (message == null || message.isEmpty() || !enabled()) {
            return;
        }
        write("CHAT", message);
    }

    /**
     * @param category a short tag such as BUILDER, PATH, or CALC, used to make the log greppable
     */
    public static void log(String category, String format, Object... args) {
        if (!enabled()) {
            return;
        }
        String message;
        if (args.length == 0) {
            message = format;
        } else {
            try {
                message = String.format(format, args);
            } catch (Throwable t) {
                message = format + " " + Arrays.toString(args);
            }
        }
        write(category, message);
    }

    /**
     * A short, readable name for a goal.
     * <p>
     * {@code toString()} on a composite prints every child, so one goal dumps four kilobytes: a path event logged once
     * per tick wrote half a megabyte a second and filled the whole session budget in a couple of minutes. Composite
     * names are all a reader needs, because the positions themselves are already in the PLAN, PLACE and BREAK lines.
     */
    public static String goal(Object goal) {
        if (goal == null) {
            return "<none>";
        }
        String name = goal.getClass().getSimpleName();
        if (name.contains("Composite") || name.contains("Janky")) {
            return name;
        }
        String text = String.valueOf(goal);
        return text.length() <= 80 ? text : name + "(...)";
    }

    /**
     * A visual break, used to separate one builder tick's worth of detail from the next event worth reading.
     */
    public static void section(String title) {
        if (!enabled()) {
            return;
        }
        writeRaw("--------------------------------------------------------------------------");
        writeRaw("== " + title);
    }

    public static void exception(String category, String what, Throwable t) {
        if (!enabled() || t == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(what).append(": ").append(t);
        for (StackTraceElement element : t.getStackTrace()) {
            sb.append('\n').append("    at ").append(element);
        }
        write(category, sb.toString());
    }

    /**
     * Called when the game shuts down so the buffered tail is not lost.
     */
    public static synchronized void close() {
        if (out == null) {
            return;
        }
        try {
            out.flush();
            out.close();
        } catch (Throwable ignored) {
        }
        out = null;
    }

    private static synchronized void write(String category, String message) {
        if (!enabled()) {
            return;
        }
        try {
            String stamp = stamp();
            for (String line : message.split("\n", -1)) {
                writeRaw(stamp + " [" + category + "] " + line);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String stamp() {
        StringBuilder sb = new StringBuilder(40);
        sb.append(LocalDateTime.now().format(TIME));
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                sb.append(" t=").append(mc.level.getGameTime());
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static synchronized void writeRaw(String line) {
        PrintStream stream = ensureOpen();
        if (stream == null) {
            return;
        }
        try {
            if (capped) {
                return;
            }
            if (bytesWritten + line.length() + 1L > MAX_BYTES) {
                capped = true;
                stream.println(stamp() + " [LOG] stopped writing: this session reached " + bytesWritten
                        + " bytes, which is enough to read. Turn #debug off and back on for a fresh file.");
                stream.flush();
                return;
            }
            stream.println(line);
            stream.flush();
            bytesWritten += line.length() + 1L;
            written++;
        } catch (Throwable ignored) {
        }
    }

    /**
     * Opens the file on first use, and re-opens it when the loaded world changed so each save gets its own log.
     */
    private static PrintStream ensureOpen() {
        try {
            Path target = resolveFile();
            if (target == null) {
                return out;
            }
            if (out != null) {
                // The very first line is written while Baritone is being constructed, which happens before a world is
                // loaded, so the path used to be pinned to the game directory for the whole session and the file never
                // showed up in the save folder. Relocate to the save directory as soon as one exists, and never move
                // back (going to the menu must not steal the file away from the world it belongs to).
                if (worldBound || target.equals(file)) {
                    return out;
                }
                closeQuietly();
                // A different file means a different session: it gets its own header and its own budget, otherwise a
                // cap reached before the world loaded would silence the file that actually matters.
                truncated = false;
                bytesWritten = 0L;
                capped = false;
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            StandardOpenOption[] options = truncated
                    ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE}
                    : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
            PrintStream stream = new PrintStream(Files.newOutputStream(target, options), false, StandardCharsets.UTF_8);
            out = stream;
            file = target;
            worldBound = worldAvailable();
            if (!truncated) {
                truncated = true;
                header(stream);
            }
            return out;
        } catch (Throwable t) {
            out = null;
            return null;
        }
    }

    private static boolean worldAvailable() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Path resolveFile() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return null;
        }
        try {
            if (mc.hasSingleplayerServer()) {
                MinecraftServer server = mc.getSingleplayerServer();
                if (server != null) {
                    return server.getWorldPath(LevelResource.ROOT).resolve("baritonelog.txt");
                }
            }
        } catch (Throwable ignored) {
        }
        if (mc.gameDirectory != null) {
            return mc.gameDirectory.toPath().resolve("baritonelog.txt");
        }
        return Path.of("baritonelog.txt").toAbsolutePath();
    }

    private static void closeQuietly() {
        try {
            if (out != null) {
                out.flush();
                out.close();
            }
        } catch (Throwable ignored) {
        } finally {
            out = null;
        }
    }

    private static void header(PrintStream stream) {
        try {
            Minecraft mc = Minecraft.getInstance();
            stream.println("##########################################################################");
            stream.println("# Baritone debug log");
            stream.println("# started   : " + LocalDateTime.now().format(STAMP));
            stream.println("# file      : " + file);
            if (mc != null && mc.level != null) {
                stream.println("# dimension : " + mc.level.dimension().identifier());
            }
            if (mc != null && mc.player != null) {
                stream.println("# player    : " + mc.player.getName().getString());
                stream.println("# feet      : " + mc.player.blockPosition().toShortString());
            }
            stream.println("# toggle    : #debug true  /  #debug false");
            stream.println("#");
            stream.println("# settings in effect at startup:");
            try {
                Settings settings = BaritoneAPI.getSettings();
                for (Settings.Setting<?> setting : settings.allSettings) {
                    if (setting.isJavaOnly()) {
                        continue;
                    }
                    stream.println("#   " + setting.getName() + " = " + setting.value);
                }
            } catch (Throwable t) {
                stream.println("#   <failed to dump settings: " + t + ">");
            }
            stream.println("##########################################################################");
            stream.flush();
        } catch (Throwable ignored) {
        }
    }
}
