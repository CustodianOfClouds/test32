import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Test harness for LZW compression/expansion
 * Validates round-trip compression works correctly
 */
public class TestLZW {

    private static class TestCase {
        String name;
        String input;

        TestCase(String name, String input) {
            this.name = name;
            this.input = input;
        }
    }

    public static void main(String[] args) throws Exception {
        List<TestCase> testCases = generateTestSequences();

        int passed = 0;
        int failed = 0;

        for (TestCase tc : testCases) {
            System.out.println("Testing: " + tc.name + " (length=" + tc.input.length() + ")");

            try {
                if (testRoundTrip(tc.input)) {
                    System.out.println("  ✓ PASSED");
                    passed++;
                } else {
                    System.out.println("  ✗ FAILED - output doesn't match input");
                    failed++;
                }
            } catch (Exception e) {
                System.out.println("  ✗ FAILED - " + e.getMessage());
                failed++;
            }

            System.out.println();
        }

        System.out.println("=================================");
        System.out.println("Results: " + passed + " passed, " + failed + " failed");
        System.out.println("=================================");

        System.exit(failed > 0 ? 1 : 0);
    }

    private static List<TestCase> generateTestSequences() {
        List<TestCase> tests = new ArrayList<>();
        Random rand = new Random(42); // Fixed seed for reproducibility

        tests.add(new TestCase("empty", ""));
        tests.add(new TestCase("single_a", "a"));
        tests.add(new TestCase("single_b", "b"));
        tests.add(new TestCase("alternating_ab", repeat("ab", 50)));
        tests.add(new TestCase("aaaaabbbbbb", "aaaaabbbbbb"));
        tests.add(new TestCase("all_a", repeat("a", 50)));
        tests.add(new TestCase("all_b", repeat("b", 50)));
        tests.add(new TestCase("random_short", randomString(rand, 20)));
        tests.add(new TestCase("random_medium", randomString(rand, 100)));
        tests.add(new TestCase("repeating_pattern", repeat("aababb", 10)));
        tests.add(new TestCase("mixed_pattern", repeat("abbaaaabbbbaa", 5)));

        // Edge cases
        tests.add(new TestCase("long_run_a_63", repeat("a", 63)));
        tests.add(new TestCase("long_run_b_64", repeat("b", 64)));
        tests.add(new TestCase("alternating_3a3b", repeat("aaabbb", 20)));
        tests.add(new TestCase("pattern_abbbbaaa", repeat("abbbbaaa", 15)));
        tests.add(new TestCase("random_long", randomString(rand, 500)));
        tests.add(new TestCase("pattern_cross_boundary", repeat("a", 7) + repeat("b", 9) + repeat("a", 15)));
        tests.add(new TestCase("sequence_edge_case", repeat("a", 3) + repeat("b", 3) + repeat("a", 4) + repeat("b", 5)));

        return tests;
    }

    private static String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    private static String randomString(Random rand, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(rand.nextBoolean() ? 'a' : 'b');
        }
        return sb.toString();
    }

    private static boolean testRoundTrip(String input) throws Exception {
        // Create alphabet file (just 'a' and 'b')
        Path alphabetFile = Files.createTempFile("alphabet", ".txt");
        Files.write(alphabetFile, "a\nb\n".getBytes());

        // Create input file
        Path inputFile = Files.createTempFile("input", ".txt");
        Files.write(inputFile, input.getBytes());

        // Create compressed file
        Path compressedFile = Files.createTempFile("compressed", ".lzw");

        // Create output file
        Path outputFile = Files.createTempFile("output", ".txt");

        try {
            // Compress
            ProcessBuilder compressBuilder = new ProcessBuilder(
                "java", "LZWTool",
                "--mode", "compress",
                "--minW", "9",
                "--maxW", "16",
                "--policy", "freeze",
                "--alphabet", alphabetFile.toString()
            );
            compressBuilder.redirectInput(inputFile.toFile());
            compressBuilder.redirectOutput(compressedFile.toFile());
            compressBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);

            Process compressProcess = compressBuilder.start();
            int compressExitCode = compressProcess.waitFor();

            if (compressExitCode != 0) {
                throw new Exception("Compression failed with exit code " + compressExitCode);
            }

            // Expand
            ProcessBuilder expandBuilder = new ProcessBuilder(
                "java", "LZWTool",
                "--mode", "expand"
            );
            expandBuilder.redirectInput(compressedFile.toFile());
            expandBuilder.redirectOutput(outputFile.toFile());
            expandBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);

            Process expandProcess = expandBuilder.start();
            int expandExitCode = expandProcess.waitFor();

            if (expandExitCode != 0) {
                throw new Exception("Expansion failed with exit code " + expandExitCode);
            }

            // Compare
            String output = new String(Files.readAllBytes(outputFile));
            return input.equals(output);

        } finally {
            // Cleanup
            Files.deleteIfExists(alphabetFile);
            Files.deleteIfExists(inputFile);
            Files.deleteIfExists(compressedFile);
            Files.deleteIfExists(outputFile);
        }
    }
}
