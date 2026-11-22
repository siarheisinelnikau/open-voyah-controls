package org.openvoyah.controls.core.voice;

import java.util.Arrays;
import java.util.List;

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
    /** Kazakh builds 11–99 as tens plus units, including the teens (он бір). */
    private static final List<String> KK_ONES = List.of("нөл", "бір", "екі", "үш", "төрт", "бес", "алты", "жеті",
        "сегіз", "тоғыз");
    private static final List<String> KK_TENS = List.of("", "он", "жиырма", "отыз", "қырық", "елу", "алпыс", "жетпіс",
        "сексен", "тоқсан");
    private static final String KK_HUNDRED = "жүз";

    static String words(int number, boolean english) {
        return words(number, english ? "en" : "ru");
    }

    static String words(int number, String language) {
        if (language.equals("kk")) {
            if (number == 100) return KK_HUNDRED;
            if (number < 10) return KK_ONES.get(number);
            int digit = number % 10;
            return KK_TENS.get(number / 10) + (digit == 0 ? "" : " " + KK_ONES.get(digit));
        }
        boolean english = language.equals("en");
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

    /**
     * The first Kazakh number from 0 to 100 written in words in [phrase] (tens, then optional units), or null. Used to
     * pass a Kazakh percentage or charge level to the vehicle agent, which reads Russian number words.
     */
    static Integer kazakh(String phrase) {
        List<String> words = Arrays.asList(phrase.trim().split("\\s+"));
        for (int index = 0; index < words.size(); index++) {
            String word = words.get(index);
            if (word.equals(KK_HUNDRED)) return 100;
            int tens = KK_TENS.indexOf(word);
            if (tens > 0) {
                int units = index + 1 < words.size() ? KK_ONES.indexOf(words.get(index + 1)) : -1;
                return tens * 10 + Math.max(units, 0);
            }
            int ones = KK_ONES.indexOf(word);
            if (ones >= 0) return ones;
        }
        return null;
    }
}
