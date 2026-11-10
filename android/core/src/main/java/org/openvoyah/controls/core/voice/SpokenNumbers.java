package org.openvoyah.controls.core.voice;

final class SpokenNumbers {
    private SpokenNumbers() { }

    private static final String[] EN_ONES = {"zero", "one", "two", "three", "four", "five", "six",
        "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
        "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] EN_TENS = {"", "", "twenty", "thirty", "forty", "fifty",
        "sixty", "seventy", "eighty", "ninety"};
    private static final String[] RU_ONES = {"ноль", "один", "два", "три", "четыре", "пять", "шесть",
        "семь", "восемь", "девять", "десять", "одиннадцать", "двенадцать", "тринадцать",
        "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"};
    private static final String[] RU_TENS = {"", "", "двадцать", "тридцать", "сорок", "пятьдесят",
        "шестьдесят", "семьдесят", "восемьдесят", "девяносто"};

    static String words(int number, boolean english) {
        if (number == 100) return english ? "one hundred" : "сто";
        String[] ones = english ? EN_ONES : RU_ONES;
        String[] tens = english ? EN_TENS : RU_TENS;
        if (number < 20) return ones[number];
        int digit = number % 10;
        return tens[number / 10] + (digit == 0 ? "" : " " + ones[digit]);
    }

    static String russianUnit(int number, String one, String few, String many) {
        if (number % 100 >= 11 && number % 100 <= 14) return many;
        return switch (number % 10) {
            case 1 -> one;
            case 2, 3, 4 -> few;
            default -> many;
        };
    }
}
