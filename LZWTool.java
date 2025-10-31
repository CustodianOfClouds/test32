import java.io.*;
import java.util.*;

/**
 * LZW compression tool with variable codeword width and configurable policies.
 */
public class LZWTool {

    public static void main(String[] args) {
        String mode = null;
        int minW = 9;
        int maxW = 16;
        String policy = "freeze";
        String alphabetPath = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mode":
                    mode = args[++i];
                    break;
                case "--minW":
                    minW = Integer.parseInt(args[++i]);
                    break;
                case "--maxW":
                    maxW = Integer.parseInt(args[++i]);
                    break;
                case "--policy":
                    policy = args[++i];
                    break;
                case "--alphabet":
                    alphabetPath = args[++i];
                    break;
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    System.exit(2);
            }
        }

        if (mode == null) {
            System.err.println("Error: --mode is required");
            System.exit(1);
        }

        if (mode.equals("compress")) {
            if (alphabetPath == null || minW > maxW) {
                System.err.println("Error: Invalid arguments");
                System.exit(1);
            }
            List<String> alphabet = loadAlphabet(alphabetPath);
            if (alphabet == null) {
                System.err.println("Error: Could not load alphabet");
                System.exit(1);
            }
            compress(minW, maxW, policy, alphabet);
        } else if (mode.equals("expand")) {
            expand();
        } else {
            System.err.println("Error: --mode must be 'compress' or 'expand'");
            System.exit(1);
        }
    }

    // Load alphabet from file (empty lines = newline)
    private static List<String> loadAlphabet(String path) {
        List<String> alphabet = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        try (FileReader fr = new FileReader(path)) {
            StringBuilder lineBuffer = new StringBuilder();
            int c;

            while ((c = fr.read()) != -1) {
                if (c == '\n') {
                    String symbol = (lineBuffer.length() == 0) ? "\n" : String.valueOf(lineBuffer.charAt(0));
                    if (!seen.contains(symbol)) {
                        seen.add(symbol);
                        alphabet.add(symbol);
                    }
                    lineBuffer.setLength(0);
                } else {
                    lineBuffer.append((char) c);
                }
            }

            if (lineBuffer.length() > 0) {
                String symbol = String.valueOf(lineBuffer.charAt(0));
                if (!seen.contains(symbol)) {
                    seen.add(symbol);
                    alphabet.add(symbol);
                }
            }
        } catch (IOException e) {
            return null;
        }
        return alphabet;
    }

    // Write header: minW, maxW, policy, alphabet
    private static void writeHeader(int minW, int maxW, String policy, List<String> alphabet) {
        BinaryStdOut.write(minW, 8);
        BinaryStdOut.write(maxW, 8);

        int policyCode = 0;
        switch (policy) {
            case "freeze":
                policyCode = 0;
                break;
            case "reset":
                policyCode = 1;
                break;
            case "lru":
                policyCode = 2;
                break;
            case "lfu":
                policyCode = 3;
                break;
        }
        BinaryStdOut.write(policyCode, 8);
        BinaryStdOut.write(alphabet.size(), 16);

        for (String symbol : alphabet) {
            BinaryStdOut.write(symbol.length() > 0 ? symbol.charAt(0) : 0, 8);
        }
    }

    private static class Header {
        int minW, maxW;
        String policy;
        List<String> alphabet;
    }

    // Read header from compressed file
    private static Header readHeader() {
        Header h = new Header();
        h.minW = BinaryStdIn.readInt(8);
        h.maxW = BinaryStdIn.readInt(8);

        int policyCode = BinaryStdIn.readInt(8);
        switch (policyCode) {
            case 0:
                h.policy = "freeze";
                break;
            case 1:
                h.policy = "reset";
                break;
            case 2:
                h.policy = "lru";
                break;
            case 3:
                h.policy = "lfu";
                break;
        }

        int alphabetSize = BinaryStdIn.readInt(16);
        h.alphabet = new ArrayList<>();
        for (int i = 0; i < alphabetSize; i++) {
            h.alphabet.add(String.valueOf(BinaryStdIn.readChar(8)));
        }
        return h;
    }

    // LZW compression with variable width
    private static void compress(int minW, int maxW, String policy, List<String> alphabet) {
        writeHeader(minW, maxW, policy, alphabet);

        TSTmod<Integer> codebook = new TSTmod<>();
        int nextCode = 0;

        for (String symbol : alphabet) {
            codebook.put(new StringBuilder(symbol), nextCode++);
        }

        int EOF_CODE = nextCode++;
        int W = minW;
        int maxCode = (1 << maxW);

        if (BinaryStdIn.isEmpty()) {
            BinaryStdOut.close();
            return;
        }

        char c = BinaryStdIn.readChar();
        StringBuilder current = new StringBuilder().append(c);

        // Validate first character is in alphabet
        Integer firstCode = codebook.get(current);
        if (firstCode == null) {
            System.err.println("Error: Input contains byte value " + (int)c +
                " (0x" + Integer.toHexString(c) + ") which is not in the alphabet");
            System.exit(1);
        }

        while (!BinaryStdIn.isEmpty()) {
            c = BinaryStdIn.readChar();
            StringBuilder next = new StringBuilder(current).append(c);

            if (codebook.contains(next)) {
                current = next;
            } else {
                Integer code = codebook.get(current);
                if (code == null) {
                    System.err.println("Error: Codebook lookup failed for string ending with byte " +
                        (int)current.charAt(current.length()-1));
                    System.exit(1);
                }
                BinaryStdOut.write(code, W);

                if (nextCode < maxCode) {
                    if (nextCode >= (1 << W) && W < maxW) {
                        W++;
                    }
                    codebook.put(next, nextCode++);
                }

                // Validate the new character is in alphabet
                StringBuilder charCheck = new StringBuilder().append(c);
                if (!codebook.contains(charCheck)) {
                    System.err.println("Error: Input contains byte value " + (int)c +
                        " (0x" + Integer.toHexString(c) + ") which is not in the alphabet");
                    System.exit(1);
                }
                current = charCheck;
            }
        }

        if (current.length() > 0) {
            Integer code = codebook.get(current);
            if (code == null) {
                System.err.println("Error: Codebook lookup failed for final string");
                System.exit(1);
            }
            BinaryStdOut.write(code, W);
        }

        BinaryStdOut.write(EOF_CODE, W);
        BinaryStdOut.close();
    }

    // LZW expansion
    private static void expand() {
        Header h = readHeader();

        int alphabetSize = h.alphabet.size();
        int maxCode = (1 << h.maxW);
        String[] decodingTable = new String[maxCode];

        for (int i = 0; i < alphabetSize; i++) {
            decodingTable[i] = h.alphabet.get(i);
        }

        int EOF_CODE = alphabetSize;
        int nextCode = alphabetSize + 1;
        int W = h.minW;

        if (BinaryStdIn.isEmpty()) {
            BinaryStdOut.close();
            return;
        }

        int prevCode = BinaryStdIn.readInt(W);
        if (prevCode == EOF_CODE) {
            BinaryStdOut.close();
            return;
        }

        String val = decodingTable[prevCode];
        BinaryStdOut.write(val);

        while (!BinaryStdIn.isEmpty()) {
            if (nextCode >= (1 << W) && W < h.maxW) {
                W++;
            }

            int codeword = BinaryStdIn.readInt(W);
            if (codeword == EOF_CODE)
                break;

            String s = decodingTable[codeword];
            if (s == null)
                s = val + val.charAt(0);

            BinaryStdOut.write(s);

            if (nextCode < maxCode) {
                decodingTable[nextCode++] = val + s.charAt(0);
            }

            val = s;
        }

        BinaryStdOut.close();
    }
}
