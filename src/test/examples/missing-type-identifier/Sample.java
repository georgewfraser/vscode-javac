package com.sample;

public class Sample {
    public static void main(String[] args) {
        // While forming the abstract syntax tree, the parser adds <error> after `arg`
        // because it thinks `arg` is the type identifier, and there should be a variable
        // name after it. This <error>, then, is not handled properly by the LS and causes
        // LS to crash, as it wants to underline it as a not-used-variable, while it's
        // non-existent in the original file.
        for (arg : args) {
        }
    }
};
