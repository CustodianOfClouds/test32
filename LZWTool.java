import java.io.*;
import java.util.*;

public class LZWTool {

    private static String mode;
    private static int minW = 9;
    private static int maxW = 16;
    private static String policy = "freeze";
    private static String alphabetPath;

    private static void validateCompressionArgs() {
        if (alphabetPath == null || minW > maxW) {
            System.err.println("Error: Invalid arguments for compression");
            System.exit(1);
        }
    }

    private static void parseArguments(String[] args) {
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
    }

    public static void main(String[] args) {
        parseArguments(args);

        if (mode.equals("compress")) {
            validateCompressionArgs();
            List<Character> alphabet = loadAlphabet(alphabetPath);

            if (alphabet == null) {
                System.err.println("Error: Could not load alphabet from " + alphabetPath);
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

    private static List<Character> loadAlphabet(String path) {
        List<Character> alphabet = new ArrayList<>();
        Set<Character> seen = new LinkedHashSet<>();
        alphabet.add('\r'); seen.add('\r');
        alphabet.add('\n'); seen.add('\n');

        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(path), "UTF-8")) {
            StringBuilder lineBuffer = new StringBuilder();
            int c;

            while ((c = reader.read()) != -1) {
                if (c == '\n') {
                    Character symbol = (lineBuffer.length() == 0) ? null : lineBuffer.charAt(0);
                    if (symbol != null && !seen.contains(symbol)) {
                        seen.add(symbol);
                        alphabet.add(symbol);
                    }
                    lineBuffer.setLength(0);
                } else {
                    lineBuffer.append((char) c);
                }
            }

            if (lineBuffer.length() > 0) {
                Character symbol = lineBuffer.charAt(0);
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

    private static long updateLRUEncoder(Map<String, Long> LRUMap, String usedPattern, long lruTimestamp) {
        if (LRUMap.containsKey(usedPattern)) {
            LRUMap.put(usedPattern, lruTimestamp++);
        }
        return lruTimestamp;
    }

    private static void compress(int minW, int maxW, String policy, List<Character> alphabet) {
        writeHeader(minW, maxW, policy, alphabet);
        TSTmod<Integer> dictionary = new TSTmod<>();
        int W = minW;
        int maxCode = 1 << maxW;

        Set<Character> alphabetSet = new HashSet<>(alphabet);
        int nextCode = 0;
        StringBuilder sb = new StringBuilder(1);
        for (Character symbol : alphabet) {
            sb.setLength(0);
            sb.append(symbol);
            dictionary.put(sb, nextCode++);
        }

        int EOF_CODE = nextCode++;
        int RESET_CODE = -1;
        if (policy.equals("reset")) {
            RESET_CODE = nextCode++;
        }

        Map<String, Long> LRUMap = new HashMap<>();
        long lruTimestamp = 0;
        Map<String, Integer> LFUMap = new HashMap<>();

        if (BinaryStdIn.isEmpty()) {
            BinaryStdOut.close();
            return;
        }

        char c = BinaryStdIn.readChar();
        if (!alphabetSet.contains(c)) {
            System.err.println("Error: Input contains byte value " + (int) c + " which is not in the alphabet");
            System.exit(1);
        }
        StringBuilder current = new StringBuilder().append(c);

        while (!BinaryStdIn.isEmpty()) {
            c = BinaryStdIn.readChar();
            if (!alphabetSet.contains(c)) {
                System.err.println("Error: Input contains byte value " + (int) c + " which is not in the alphabet");
                System.exit(1);
            }
            StringBuilder next = new StringBuilder(current).append(c);

            if (dictionary.contains(next)) {
                current = next;
            } else {
                BinaryStdOut.write(dictionary.get(current), W);
                if (policy.equals("lru")) {
                    lruTimestamp = updateLRUEncoder(LRUMap, current.toString(), lruTimestamp);
                }

                if (nextCode < maxCode) {
                    if (nextCode >= (1 << W) && W < maxW) {
                        W++;
                    }

                    dictionary.put(next, nextCode++);
                    if (policy.equals("lru")) {
                        LRUMap.put(next.toString(), lruTimestamp++);
                    }

                } else {
                    switch (policy) {
                        case "freeze":
                            break;
                        case "reset":
                            if (nextCode >= (1 << W) && W < maxW) {
                                W++;
                            }
                            BinaryStdOut.write(RESET_CODE, W);
                            dictionary = new TSTmod<>();
                            nextCode = 0;
                            sb.setLength(0);
                            for (Character symbol : alphabet) {
                                sb.setLength(0);
                                sb.append(symbol);
                                dictionary.put(sb, nextCode++);
                            }
                            nextCode += 2;
                            W = minW;
                            break;
                        case "lru":
                            String lruPattern = null;
                            long oldestTimestamp = Long.MAX_VALUE;

                            for (Map.Entry<String, Long> entry : LRUMap.entrySet()) {
                                if (entry.getValue() < oldestTimestamp) {
                                    oldestTimestamp = entry.getValue();
                                    lruPattern = entry.getKey();
                                }
                            }

                            if (lruPattern != null) {
                                Integer evictedCode = dictionary.get(new StringBuilder(lruPattern));
                                dictionary.put(new StringBuilder(lruPattern), null);
                                LRUMap.remove(lruPattern);
                                dictionary.put(next, evictedCode);
                                LRUMap.put(next.toString(), lruTimestamp++);
                            }
                            break;
                        case "lfu":
                            break;
                        default:
                            break;
                    }
                }

                current = new StringBuilder().append(c);
            }
        }

        if (current.length() > 0) {
            BinaryStdOut.write(dictionary.get(current), W);
        }
        if (nextCode >= (1 << W) && W < maxW) {
            W++;
        }

        BinaryStdOut.write(EOF_CODE, W);
        BinaryStdOut.close();
    }

    private static void expand() {
        Header h = readHeader();
        int maxCode = 1 << h.maxW;
        int W = h.minW;

        int EOF_CODE = h.alphabetSize;
        int nextCode = h.alphabetSize + 1;
        int RESET_CODE = -1;
        if (h.policy == 1) {
            RESET_CODE = h.alphabetSize + 1;
            nextCode++;
        }

        Map<Integer, Long> LRUMap = new HashMap<>();
        long lruTimestamp = 0;
        Map<String, Integer> LFUMap = new HashMap<>();

        String[] dictionary = new String[maxCode];
        for (int i = 0; i < h.alphabetSize; i++) {
            dictionary[i] = h.alphabet.get(i).toString();
        }

        if (BinaryStdIn.isEmpty()) {
            BinaryStdOut.close();
            return;
        }

        int current = BinaryStdIn.readInt(W);
        if (current == EOF_CODE) {
            BinaryStdOut.close();
            return;
        }

        if (current < h.alphabetSize) {
            BinaryStdOut.write(dictionary[current]);
        } else {
            System.err.println("Bad compressed code: " + current);
            System.exit(1);
        }
        String valPrior = dictionary[current];

        while (!BinaryStdIn.isEmpty()) {
            if (nextCode >= (1 << W) && W < h.maxW) {
                W++;
            }

            current = BinaryStdIn.readInt(W);
            if (current == EOF_CODE) {
                break;
            }

            if (h.policy == 1 && current == RESET_CODE) {
                for (int i = h.alphabetSize; i < dictionary.length; i++) {
                    dictionary[i] = null;
                }
                nextCode = h.alphabetSize + 2;
                W = h.minW;
                current = BinaryStdIn.readInt(W);
                if (current == EOF_CODE) {
                    break;
                }
                valPrior = dictionary[current];
                BinaryStdOut.write(valPrior);
                continue;
            }

            String s = "";
            if (current < nextCode) {
                s = dictionary[current];
            } else if (current == nextCode) {
                StringBuilder tempSb = new StringBuilder(valPrior.length() + 1);
                tempSb.append(valPrior).append(valPrior.charAt(0));
                s = tempSb.toString();
            } else {
                System.err.println("Bad compressed code: " + current);
                System.exit(1);
            }

            BinaryStdOut.write(s);

            if (nextCode < maxCode) {
                StringBuilder tempSb = new StringBuilder(valPrior.length() + 1);
                tempSb.append(valPrior).append(s.charAt(0));
                dictionary[nextCode] = tempSb.toString();

                if (h.policy == 2) {
                    LRUMap.put(nextCode, lruTimestamp++);
                }

                nextCode++;

                if (h.policy == 2 && current >= h.alphabetSize) {
                    if (LRUMap.containsKey(current)) {
                        LRUMap.put(current, lruTimestamp++);
                    }
                }

            } else {
                switch (h.policy) {
                    case 0:
                        break;
                    case 1:
                        break;
                    case 2:
                        int lruCode = -1;
                        long oldestTimestamp = Long.MAX_VALUE;

                        for (Map.Entry<Integer, Long> entry : LRUMap.entrySet()) {
                            if (entry.getValue() < oldestTimestamp) {
                                oldestTimestamp = entry.getValue();
                                lruCode = entry.getKey();
                            }
                        }

                        if (lruCode != -1) {
                            StringBuilder tempSb2 = new StringBuilder(valPrior.length() + 1);
                            tempSb2.append(valPrior).append(s.charAt(0));
                            dictionary[lruCode] = tempSb2.toString();
                            LRUMap.put(lruCode, lruTimestamp++);
                        }

                        break;
                    case 3:
                        break;
                    default:
                        break;
                }
            }

            valPrior = s;
        }
        BinaryStdOut.close();
    }

    private static class Header {
        int minW;
        int maxW;
        int policy;
        List<Character> alphabet;
        int alphabetSize;
    }

    private static void writeHeader(int minW, int maxW, String policy, List<Character> alphabet) {
        BinaryStdOut.write(minW, 8);
        BinaryStdOut.write(maxW, 8);

        int policyCode;
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
            default:
                policyCode = 0;
                break;
        }
        BinaryStdOut.write(policyCode, 8);
        BinaryStdOut.write(alphabet.size(), 16);

        for (Character symbol : alphabet) {
            BinaryStdOut.write(symbol != null ? symbol : 0, 8);
        }
    }

    private static Header readHeader() {
        Header header = new Header();

        header.minW = BinaryStdIn.readInt(8);
        header.maxW = BinaryStdIn.readInt(8);
        header.policy = BinaryStdIn.readInt(8);

        int alphabetSize = BinaryStdIn.readInt(16);
        header.alphabetSize = alphabetSize;

        header.alphabet = new ArrayList<>();
        for (int i = 0; i < alphabetSize; i++) {
            header.alphabet.add(BinaryStdIn.readChar(8));
        }

        return header;
    }
}
