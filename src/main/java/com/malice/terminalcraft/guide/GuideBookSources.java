package com.malice.terminalcraft.guide;

import com.malice.terminalcraft.plc.PlcProgramTemplates;

/** Generated guide sections that should remain exactly aligned with executable game content. */
public final class GuideBookSources {
    private GuideBookSources() {}

    /** Builds searchable Markdown containing every compile-tested built-in PLC program template. */
    public static String plcTemplateLibrary() {
        StringBuilder guide = new StringBuilder("""
                # PLC Example Program Library

                These are the exact compile-tested programs available through plc template list,
                plc template show <name>, the PLC Programmer, and the graphical Control Center.
                Copy a program as a starting point, then change its faces, bundled channels, timer
                values, setpoints, and fail-safe behavior to match the physical machine.

                """);
        for (String category : PlcProgramTemplates.categories()) {
            guide.append("## PLC Examples: ").append(capitalize(category)).append("\n\n");
            for (PlcProgramTemplates.Template template : PlcProgramTemplates.byCategory(category)) {
                guide.append("### ").append(template.id()).append(" — ")
                        .append(template.title()).append("\n\n")
                        .append(template.description()).append("\n\n")
                        .append("```text\n")
                        .append(template.source().strip()).append("\n")
                        .append("```\n\n");
            }
        }
        return guide.toString();
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) return "General";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
