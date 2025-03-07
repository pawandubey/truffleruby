/*
 * Copyright (c) 2016, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.rope;

import java.util.HashMap;
import java.util.Map;

import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;

public class RopeConstants {

    public static final Map<String, LeafRope> ROPE_CONSTANTS = new HashMap<>();

    public static final byte[] EMPTY_BYTES = new byte[0];

    public static final LeafRope EMPTY_ASCII_8BIT_ROPE = withHashCode(
            new AsciiOnlyLeafRope(EMPTY_BYTES, ASCIIEncoding.INSTANCE));
    public static final LeafRope EMPTY_US_ASCII_ROPE = withHashCode(
            new AsciiOnlyLeafRope(EMPTY_BYTES, USASCIIEncoding.INSTANCE));
    public static final LeafRope EMPTY_UTF8_ROPE = withHashCode(
            new AsciiOnlyLeafRope(EMPTY_BYTES, UTF8Encoding.INSTANCE));

    public static final LeafRope[] UTF8_SINGLE_BYTE_ROPES = new LeafRope[256];
    public static final LeafRope[] US_ASCII_SINGLE_BYTE_ROPES = new LeafRope[256];
    public static final LeafRope[] ASCII_8BIT_SINGLE_BYTE_ROPES = new LeafRope[256];

    static {
        for (int i = 0; i < 128; i++) {
            final byte[] bytes = new byte[]{ (byte) i };

            UTF8_SINGLE_BYTE_ROPES[i] = withHashCode(new AsciiOnlyLeafRope(bytes, UTF8Encoding.INSTANCE));
            US_ASCII_SINGLE_BYTE_ROPES[i] = withHashCode(new AsciiOnlyLeafRope(bytes, USASCIIEncoding.INSTANCE));
            ASCII_8BIT_SINGLE_BYTE_ROPES[i] = withHashCode(new AsciiOnlyLeafRope(bytes, ASCIIEncoding.INSTANCE));
        }

        for (int i = 128; i < 256; i++) {
            final byte[] bytes = new byte[]{ (byte) i };

            UTF8_SINGLE_BYTE_ROPES[i] = withHashCode(new InvalidLeafRope(bytes, UTF8Encoding.INSTANCE, 1));
            US_ASCII_SINGLE_BYTE_ROPES[i] = withHashCode(new InvalidLeafRope(bytes, USASCIIEncoding.INSTANCE, 1));
            ASCII_8BIT_SINGLE_BYTE_ROPES[i] = withHashCode(new ValidLeafRope(bytes, ASCIIEncoding.INSTANCE, 1));
        }
    }

    public static final Rope AMPERSAND = ascii("&");
    public static final Rope AMPERSAND_AMPERSAND = ascii("&&");
    public static final Rope AMPERSAND_DOT = ascii("&.");
    public static final Rope BACKTICK = ascii("`");
    public static final Rope BACKSLASH = ascii("\\");
    public static final Rope BANG = ascii("!");
    public static final Rope BANG_EQ = ascii("!=");
    public static final Rope BANG_TILDE = ascii("!~");
    public static final Rope CALL = ascii("call");
    public static final Rope CARET = ascii("^");
    public static final Rope COLON = ascii(":");
    public static final Rope COLON_COLON = ascii("::");
    public static final Rope COMMA = ascii(",");
    public static final Rope DOT = ascii(".");
    public static final Rope DOT_DOT = ascii("..");
    public static final Rope DOT_DOT_DOT = ascii("...");
    public static final Rope DOLLAR_BANG = ascii("$!");
    public static final Rope DOLLAR_ZERO = ascii("$0");
    public static final Rope EQ = ascii("=");
    public static final Rope EQ_EQ = ascii("==");
    public static final Rope EQ_EQ_EQ = ascii("===");
    public static final Rope EQ_GT = ascii("=>");
    public static final Rope EQ_TILDE = ascii("=~");
    public static final Rope GT = ascii(">");
    public static final Rope GT_EQ = ascii(">=");
    public static final Rope GT_GT = ascii(">>");
    public static final Rope LBRACKET = ascii("[");
    public static final Rope LBRACKET_RBRACKET = ascii("[]");
    public static final Rope LBRACKET_RBRACKET_EQ = ascii("[]=");
    public static final Rope LCURLY = ascii("{");
    public static final Rope LT = ascii("<");
    public static final Rope LT_EQ = ascii("<=");
    public static final Rope LT_EQ_GT = ascii("<=>");
    public static final Rope LT_LT = ascii("<<");
    public static final Rope MINUS = ascii("-");
    public static final Rope MINUS_AT = ascii("-@");
    public static final Rope MINUS_GT = ascii("->");
    public static final Rope OR = ascii("|");
    public static final Rope OR_OR = ascii("||");
    public static final Rope PERCENT = ascii("%");
    public static final Rope PLUS = ascii("+");
    public static final Rope PLUS_AT = ascii("+@");
    public static final Rope Q = ascii("'");
    public static final Rope QQ = ascii("\"");
    public static final Rope QUESTION = ascii("?");
    public static final Rope RBRACKET = ascii("]");
    public static final Rope RCURLY = ascii("}");
    public static final Rope RPAREN = ascii(")");
    public static final Rope SEMICOLON = ascii(";");
    public static final Rope SLASH = ascii("/");
    public static final Rope STAR = ascii("*");
    public static final Rope STAR_STAR = ascii("**");
    public static final Rope TILDE = ascii("~");

    private static Rope ascii(String string) {
        if (string.length() == 1) {
            return US_ASCII_SINGLE_BYTE_ROPES[string.charAt(0)];
        } else {
            final byte[] bytes = RopeOperations.encodeAsciiBytes(string);
            final LeafRope rope = withHashCode(new AsciiOnlyLeafRope(bytes, USASCIIEncoding.INSTANCE));
            final Rope existing = ROPE_CONSTANTS.putIfAbsent(string, rope);
            if (existing != null) {
                throw new AssertionError("Duplicate Rope in RopeConstants: " + existing);
            }
            return rope;
        }
    }

    public static LeafRope lookupUSASCII(String string) {
        if (string.length() == 1) {
            return US_ASCII_SINGLE_BYTE_ROPES[string.charAt(0)];
        } else {
            return ROPE_CONSTANTS.get(string);
        }
    }

    private static final LeafRope[] PADDED_NUMBERS = createPaddedNumbersTable();

    private static LeafRope[] createPaddedNumbersTable() {
        final LeafRope[] table = new LeafRope[100];

        for (int n = 0; n < table.length; n++) {
            table[n] = new AsciiOnlyLeafRope(
                    new byte[]{ (byte) ('0' + n / 10), (byte) ('0' + n % 10) },
                    UTF8Encoding.INSTANCE);
        }

        return table;
    }

    /*** Zero-padded numbers in the format %02d, between 00 and 99. */
    public static LeafRope paddedNumber(int n) {
        return PADDED_NUMBERS[n];
    }

    private static final LeafRope[] PADDING_ZEROS = createPaddingZeroTable();

    private static LeafRope[] createPaddingZeroTable() {
        final LeafRope[] table = new LeafRope[6];

        for (int n = 0; n < table.length; n++) {
            final byte[] bytes = new byte[n];

            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = '0';
            }

            table[n] = new AsciiOnlyLeafRope(bytes, UTF8Encoding.INSTANCE);
        }

        return table;
    }

    public static LeafRope paddingZeros(int n) {
        return PADDING_ZEROS[n];
    }

    private static <T> T withHashCode(T object) {
        object.hashCode();
        return object;
    }

}
