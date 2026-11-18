// SPDX-License-Identifier: AGPL-3.0-only
package dev.trashpanda.storageterminal.mc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;

/**
 * DEV TOOL, not a test: builds a survival world with a filled storage room around spawn and
 * copies it to {@code run/saves/storage-test} for manual testing with {@code runClient}.
 * Only runs when the file {@code build/make-test-world} exists; the other in-game tests are
 * skipped then. Usage: create that file, run {@code ./gradlew runClientGameTest}, then
 * {@code ./gradlew runClient --args="--quickPlaySingleplayer storage-test"}.
 */
public final class TestWorldMaker implements FabricClientGameTest {
    private BlockPos origin;
    private TestServerContext server;

    /** True when this run should only make the test world. */
    static boolean requested() {
        return Files.exists(projectDir().resolve("build").resolve("make-test-world"));
    }

    private static Path projectDir() {
        // Game dir is <project>/build/run/clientGameTest.
        return FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize().getParent().getParent().getParent();
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!requested()) {
            return;
        }
        TestWorldSave save;
        try (TestSingleplayerContext world = context.worldBuilder()
                .adjustSettings(s -> {
                    s.setName("Storage Test");
                    s.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
                    s.setDifficulty(Difficulty.PEACEFUL);
                    s.setAllowCommands(true);
                })
                .create()) {
            save = world.getWorldSave();
            server = world.getServer();
            origin = server.computeOnServer(s -> s.getPlayerList().getPlayers().get(0).blockPosition());
            run("time set day");
            run("weather clear");
            buildRoom();
            run("clear @a");
            run("give @a minecraft:cobblestone 64");
            run("give @a minecraft:dirt 30");
            run("give @a minecraft:oak_sapling 5");
            run("give @a minecraft:iron_ingot 9");
            run("give @a minecraft:torch 32");
            world.getConnection().waitForChunksDownload();
            context.waitTicks(20);
        }
        copyTo(save.getSaveDirectory(), projectDir().resolve("run").resolve("saves").resolve("storage-test"));
    }

    private void buildRoom() {
        // South row (z = +3), chests facing north toward spawn. "left" connects to the east here.
        place(-3, 3, "minecraft:chest[facing=north,type=left]");
        place(-2, 3, "minecraft:chest[facing=north,type=right]");
        fill(-3, 3, 0, List.of("cobblestone 64", "cobblestone 64", "cobblestone 64", "cobblestone 64", "cobblestone 64",
                "stone 64", "stone 64", "stone 64", "dirt 64", "dirt 64", "dirt 64", "dirt 64", "oak_log 64", "oak_log 64",
                "oak_log 64", "oak_planks 64", "oak_planks 64", "glass 32", "sand 64", "sand 64", "gravel 40"));
        fill(-2, 3, 0, List.of("cobblestone 64", "cobblestone 64", "cobblestone 37", "stone 12", "andesite 64",
                "granite 50", "diorite 33", "deepslate 64", "tuff 20"));

        place(-1, 3, "minecraft:chest[facing=north,type=left]");
        place(0, 3, "minecraft:chest[facing=north,type=right]");
        fill(-1, 3, 0, List.of("iron_ingot 64", "iron_ingot 64", "iron_ingot 17", "gold_ingot 23", "copper_ingot 64",
                "copper_ingot 64", "coal 64", "coal 64", "coal 64", "coal 30", "redstone 64", "redstone 30"));
        fill(0, 3, 0, List.of("lapis_lazuli 45", "diamond 12", "emerald 7", "quartz 60", "amethyst_shard 18",
                "raw_iron 64", "raw_iron 22", "raw_gold 9", "raw_copper 41"));

        place(1, 3, "minecraft:chest[facing=north]");
        fill(1, 3, 0, List.of("bread 32", "cooked_beef 20", "apple 15", "carrot 64", "potato 64", "golden_carrot 8",
                "wheat 44", "wheat_seeds 64", "sugar_cane 30"));

        place(2, 3, "minecraft:trapped_chest[facing=north]");
        fill(2, 3, 0, List.of("tnt 5", "gunpowder 40", "flint 18"));

        place(3, 3, "minecraft:barrel[facing=up]");
        fill(3, 3, 0, List.of("bone 64", "bone_meal 50", "string 37", "rotten_flesh 64", "spider_eye 9", "ender_pearl 16",
                "slime_ball 12", "feather 23", "leather 14"));

        // North row (z = -3), chests facing south. "left" connects to the west here.
        place(-2, -3, "minecraft:chest[facing=south,type=left]");
        place(-3, -3, "minecraft:chest[facing=south,type=right]");
        fill(-2, -3, 0, List.of("iron_pickaxe[damage=50] 1", "iron_pickaxe 1", "diamond_sword 1", "bow 1", "arrow 64",
                "arrow 20", "torch 64", "torch 64", "bucket 3", "shield 1", "iron_ingot 10", "shears 1"));
        fill(-3, -3, 0, List.of("oak_sapling 12", "birch_sapling 9", "white_bed 1", "crafting_table 3", "furnace 4",
                "chest 6", "ladder 40", "rail 64", "powered_rail 12"));

        place(-1, -3, "minecraft:chest[facing=south]");
        fill(-1, -3, 0, List.of("iron_ingot 40", "cobblestone 20", "dirt 7", "gold_ingot 5", "stick 33"));

        place(0, -3, "minecraft:barrel[facing=up]");
        fill(0, -3, 0, List.of("white_wool 64", "red_wool 30", "blue_wool 25", "black_wool 8"));

        place(1, -3, "minecraft:barrel[facing=up]");  // empty: room for storing

        place(2, -3, "minecraft:shulker_box[facing=up]");
        fill(2, -3, 0, List.of("diamond 5", "golden_apple 3", "ender_pearl 8", "experience_bottle 20"));

        // A chest that can't open (stone on top): its emeralds must never show.
        place(3, -3, "minecraft:chest[facing=south]");
        run(setblock(3, 1, -3, "minecraft:stone"));
        fill(3, -3, 0, List.of("emerald 64"));

        // East wall: a mostly empty chest, plus one behind a wall of stone (still reachable).
        place(3, 0, "minecraft:chest[facing=west]");
        fill(3, 0, 0, List.of("iron_ingot 5"));
        run(setblock(-3, 0, 0, "minecraft:stone"));
        run(setblock(-3, 1, 0, "minecraft:stone"));
        place(-4, 0, "minecraft:chest[facing=east]");
        fill(-4, 0, 0, List.of("redstone 20", "repeater 6", "comparator 2"));

        // Out of reach: never shown until you walk over.
        place(9, 0, "minecraft:chest[facing=west]");
        fill(9, 0, 0, List.of("netherite_ingot 2"));
    }

    private void place(int dx, int dz, String block) {
        run(setblock(dx, 0, dz, block));
    }

    /** Puts "item count" entries into consecutive slots starting at {@code firstSlot}. */
    private void fill(int dx, int dz, int firstSlot, List<String> items) {
        BlockPos pos = origin.offset(dx, 0, dz);
        int slot = firstSlot;
        for (String entry : items) {
            int space = entry.lastIndexOf(' ');
            run("item replace block " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " container." + slot
                    + " with minecraft:" + entry.substring(0, space) + " " + entry.substring(space + 1));
            slot++;
        }
    }

    private String setblock(int dx, int dy, int dz, String block) {
        BlockPos pos = origin.offset(dx, dy, dz);
        return "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block;
    }

    private void run(String command) {
        server.runCommand(command);
    }

    private static void copyTo(Path from, Path to) {
        try {
            if (Files.exists(to)) {
                try (Stream<Path> old = Files.walk(to)) {
                    old.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
            try (Stream<Path> files = Files.walk(from)) {
                for (Path source : (Iterable<Path>) files::iterator) {
                    Path target = to.resolve(from.relativize(source).toString());
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else if (!source.getFileName().toString().equals("session.lock")) {
                        Files.copy(source, target);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
