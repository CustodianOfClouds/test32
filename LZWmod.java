/*************************************************************************
 *  Compilation:  javac LZWmod.java
 *  Compression: java LZWmod - < code.txt > code.lzw
 *  Expansion: java LZWmod + < code.lzw > code.rec
 *  Checking if the expanded file is the same as the original one: diff code.txt code.rec
 *
 *  In the previous commands, replace code.txt with other files inside the Test Files folder. Make sure that you either 
 *  place the file in the same folder as the java file or include the path to the file in the command (e.g., "Test Files\code.txt"). 
 *  The input files must be in the lab’s root folder (i.e., not inside the Code folder) for debugging.
 *  Dependencies: BinaryStdIn.java BinaryStdOut.java
 *
 *  Compress or expand binary input from standard input using LZW.
 *
 *
 *************************************************************************/

public class LZWmod {
    private static final int R = 256;        // number of input chars
    private static final int L = 4096;       // number of codewords = 2^W
    private static final int W = 12;         // codeword width

    public static void compress() {
        TSTmod<Integer> st = new TSTmod<Integer>();
        for (int i = 0; i < R; i++)
            st.put(new StringBuilder("" + (char) i), i);
        int code = R+1;  // R is codeword for EOF

        //initialize the current string
        StringBuilder current = new StringBuilder();

        //read and append the first char
        char c = BinaryStdIn.readChar();
        current.append(c);

        Integer codeword = st.get(current);
        while (!BinaryStdIn.isEmpty()) {
            codeword = st.get(current);
            // read and append the next char to current
            if (BinaryStdIn.isEmpty()) break;
            c = BinaryStdIn.readChar();
            current.append(c);

            if(!st.contains(current)){
              BinaryStdOut.write(codeword, W);
              if (code < L)    // Add to symbol table if not full
                  st.put(current, code++);
                //reset current to be the new char
                current = new StringBuilder("" + c);
            }
        }

        //Write the codeword of whatever remains in current
        if (current.length() > 0) {
            codeword = st.get(current);
            BinaryStdOut.write(codeword, W);
        }

        BinaryStdOut.write(R, W); //Write EOF
        BinaryStdOut.close();
    }


    public static void expand() {
        String[] st = new String[L];
        int i; // next available codeword value

        // initialize symbol table with all 1-character strings
        for (i = 0; i < R; i++)
            st[i] = "" + (char) i;
        st[i++] = "";                        // (unused) lookahead for EOF

        int codeword = BinaryStdIn.readInt(W);
        String val = st[codeword];

        while (true) {
            BinaryStdOut.write(val);
            codeword = BinaryStdIn.readInt(W);
            if (codeword == R) break;
            String s = st[codeword];
            if (i == codeword) s = val + val.charAt(0);   // special case hack
            if (i < L) st[i++] = val + s.charAt(0);
            val = s;

        }
        BinaryStdOut.close();
    }



    public static void main(String[] args) {
        if      (args[0].equals("-")) compress();
        else if (args[0].equals("+")) expand();
        else throw new RuntimeException("Illegal command line argument");
    }

}
