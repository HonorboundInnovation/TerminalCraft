package com.malice.terminalcraft.guide;

import java.nio.file.Files;
import java.nio.file.Path;

import com.malice.terminalcraft.plc.PlcProgramTemplates;

/** Headless coverage for the complete Markdown-backed in-game guide. */
public final class GuideBookDocumentTest {
    private GuideBookDocumentTest() {}

    public static void main(String[] args) throws Exception {
        String guideSource = Files.readString(Path.of("docs/TERMINALCRAFT_GUIDE.md"));
        String cookbookSource = Files.readString(Path.of("docs/ADVANCED_SCRIPT_COOKBOOK.md"));
        Path bundledGuide = Path.of("build/resources/main/assets/terminalcraft/guide/terminalcraft_guide.md");
        Path bundledCookbook = Path.of("build/resources/main/assets/terminalcraft/guide/advanced_script_cookbook.md");
        require(Files.exists(bundledGuide) && Files.mismatch(Path.of("docs/TERMINALCRAFT_GUIDE.md"),
                        bundledGuide) == -1,
                "canonical guide is bundled byte-for-byte into the game resources");
        require(Files.exists(bundledCookbook) && Files.mismatch(Path.of("docs/ADVANCED_SCRIPT_COOKBOOK.md"),
                        bundledCookbook) == -1,
                "advanced cookbook is bundled byte-for-byte into the game resources");
        require(Files.readString(Path.of("src/main/resources/data/terminalcraft/recipes/guide_book.json"))
                        .contains("terminalcraft:guide_book"),
                "guide item has a crafting recipe");
        GuideBookDocument guide = GuideBookDocument.parse(guideSource);
        GuideBookDocument cookbook = GuideBookDocument.parse(cookbookSource);
        GuideBookDocument plcExamples = GuideBookDocument.parse(GuideBookSources.plcTemplateLibrary());
        GuideBookDocument complete = GuideBookDocument.combine(guide, cookbook, plcExamples);

        require(guide.chapters().size() >= 22, "main manual exposes its full chapter structure");
        require(complete.chapters().size() > guide.chapters().size(),
                "advanced cookbook is bundled into the same reader");
        require(!complete.search("scripting language").isEmpty(), "Bash topics are searchable");
        require(!complete.search("PID LEVEL_LOOP").isEmpty(), "PLC language topics are searchable");
        require(!complete.search("Create clutch").isEmpty(), "Create automation examples are searchable");
        require(!complete.search("SCADA supervisory control").isEmpty(), "SCADA architecture is searchable");
        require(!complete.search("scada alarm add").isEmpty(), "SCADA alarm workflow is searchable");
        require(!complete.search("scada command").isEmpty(), "SCADA PLC command workflow is searchable");
        require(!complete.search("graphical designer").isEmpty(), "advanced HMI designer workflow is searchable");
        require(!complete.search("scada hmi widget add").isEmpty(), "advanced HMI widget examples are searchable");
        require(!complete.search("modem scada request").isEmpty(), "SCADA RedNet gateway is searchable");
        for (PlcProgramTemplates.Template template : PlcProgramTemplates.all()) {
            require(!complete.search(template.id()).isEmpty(),
                    "built-in PLC example is searchable: " + template.id());
        }
        require(complete.chapters().stream().flatMap(chapter -> chapter.lines().stream())
                        .anyMatch(line -> line.kind() == GuideBookDocument.Kind.CODE
                                && line.text().contains("RUNG MOTOR")),
                "PLC sample source remains a rendered code block");
        require(complete.chapters().stream().flatMap(chapter -> chapter.lines().stream())
                        .anyMatch(line -> line.kind() == GuideBookDocument.Kind.CODE
                                && line.text().contains("#!/bin/bash")),
                "Bash sample scripts remain rendered code blocks");
        require(guide.chapters().stream().flatMap(chapter -> chapter.lines().stream())
                        .filter(line -> line.kind() == GuideBookDocument.Kind.IMAGE)
                        .count() >= 1,
                "wiring chapters retain the bundled-cable family illustration");
        require(!guide.search("Bundled Red Alloy and Bundled Network cable families").isEmpty(),
                "current guide image alternative text remains searchable");
        require(Files.exists(Path.of("src/main/resources/assets/terminalcraft/textures/gui/guide/"
                        + "bundled_cable_families.png")),
                "current guide illustration resource is packaged with the client");

        GuideBookDocument bounded = GuideBookDocument.parse("# Test\n\n## One\nText\ncontinued\n\n```sh\necho ok\n```");
        require(bounded.chapters().size() == 2, "welcome and numbered chapters parse independently");
        require(bounded.chapters().get(1).lines().stream()
                        .anyMatch(line -> line.kind() == GuideBookDocument.Kind.BODY
                                && line.text().equals("Text continued")),
                "wrapped Markdown paragraphs join into readable prose");
        GuideBookDocument rejectedImage = GuideBookDocument.parse(
                "# Test\n\n![unsafe](terminalcraft:textures/gui/guide/../other.png)");
        require(rejectedImage.chapters().stream().flatMap(chapter -> chapter.lines().stream())
                        .noneMatch(line -> line.kind() == GuideBookDocument.Kind.IMAGE),
                "guide image parser rejects parent-path traversal outside the manual allowlist");

        System.out.println("Guide book document tests: OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
