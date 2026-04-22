package org.equimacs.cli.util;

import java.util.*;

/**
 * Equimacs "No Magic" CLI Parser.
 * A zero-dependency, manual-style parser that extracts flags, options, and positional args.
 */
public record CliArgs(Map<String, String> options, Set<String> flags, List<String> positional) {
    
    public static CliArgs parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        Set<String> flags = new HashSet<>();
        List<String> positional = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--") || (arg.startsWith("-") && arg.length() > 1)) {
                // If the next arg exists and doesn't start with a dash, treat as value
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    options.put(normalize(arg), args[++i]);
                } else {
                    flags.add(normalize(arg));
                }
            } else {
                positional.add(arg);
            }
        }
        return new CliArgs(options, flags, positional);
    }

    public boolean hasFlag(String... names) {
        return Arrays.stream(names).anyMatch(flags::contains);
    }

    public String getOption(String... names) {
        for (String name : names) {
            String val = options.get(name);
            if (val != null) return val;
        }
        return null;
    }

    public boolean isDiscovery() {
        return hasFlag("help", "h", "discovery", "schema") || positional.isEmpty();
    }

    private static String normalize(String s) {
        return s.startsWith("--") ? s.substring(2) : s.substring(1);
    }
}
