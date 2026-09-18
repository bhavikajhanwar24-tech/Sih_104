package com.sentinelvoice.challenge;

import com.sentinelvoice.challenge.model.ActiveChallenge;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SecureRandom challenge phrases: [colour/adjective] [noun] [2-digit number].
 * Space ≥ 40×40×90 = 144,000. Never repeats within a session.
 */
@Component
public class ChallengePhraseGenerator {

    private static final List<String> EN_ADJ = List.of(
            "Amber", "Silver", "Cobalt", "Crimson", "Ivory", "Jade", "Azure", "Coral",
            "Golden", "Scarlet", "Violet", "Copper", "Emerald", "Indigo", "Marble", "Onyx",
            "Pearl", "Ruby", "Sapphire", "Topaz", "Bronze", "Cedar", "Coral", "Cypress",
            "Flaxen", "Granite", "Hazel", "Ivory", "Jasper", "Kelp", "Linen", "Moss",
            "Nimbus", "Olive", "Plum", "Quartz", "Russet", "Slate", "Teal", "Umber",
            "Verdant", "Walnut", "Xeric", "Yellow", "Zinc", "Arctic", "Blush", "Chartreuse"
    );

    private static final List<String> EN_NOUN = List.of(
            "Falcon", "Harbor", "Orbit", "Summit", "Vertex", "Anchor", "Beacon", "Cipher",
            "Delta", "Eagle", "Forge", "Glacier", "Horizon", "Island", "Jaguar", "Kestrel",
            "Lantern", "Meteor", "Nexus", "Osprey", "Pinnacle", "Quarry", "Ranger", "Sparrow",
            "Timber", "Umbra", "Voyager", "Willow", "Zephyr", "Atlas", "Boulder", "Canyon",
            "Dolphin", "Ember", "Flint", "Griffin", "Heron", "Iris", "Juniper", "Kraken",
            "Lotus", "Mirage", "Nebula", "Orchid", "Phoenix", "Quill", "Raven", "Cedar"
    );

    /** Romanised Hindi adjectives/colours for ASR-friendly demos. */
    private static final List<String> HI_ADJ = List.of(
            "Lal", "Neela", "Hara", "Peela", "Safed", "Kala", "Gulabi", "Bhura",
            "Sunhara", "Chandi", "Jamuni", "Asmani", "Kesari", "Badami", "Sunehra", "Dhani",
            "Rakt", "Megh", "Shwet", "Syah", "Narangi", "Bhoora", "Gulnar", "Nilam",
            "Pushp", "Komal", "Tez", "Shant", "Ujjwal", "Gahan", "Madhur", "Tikha",
            "Naram", "Sakht", "Pavitra", "Nirala", "Prabal", "Sukoon", "Chanchal", "Gambhir",
            "Udaar", "Veer", "Sundar", "Nirbhay", "Prakash", "Andhera", "Sheetal", "Ushna"
    );

    private static final List<String> HI_NOUN = List.of(
            "Baaz", "Hiran", "Mor", "Sher", "Gagan", "Sagar", "Pahar", "Nadi",
            "Deepak", "Chakra", "Kamal", "Vajra", "Tara", "Chandra", "Surya", "Megh",
            "Vayu", "Agni", "Jal", "Prithvi", "Indra", "Garud", "Mayur", "Singh",
            "Vyaghra", "Hastin", "Ashva", "Gaja", "Nakshatra", "Usha", "Sandhya", "Prahar",
            "Kshitij", "Dwar", "Mandir", "Van", "Pushp", "Phool", "Patthar", "Path",
            "Yatri", "Yodha", "Raja", "Senani", "Nayak", "Mitra", "Bandhu", "Saathi"
    );

    /** Romanised Tamil adjectives/colours. */
    private static final List<String> TA_ADJ = List.of(
            "Sivappu", "Neelam", "Pachai", "Manjal", "Vellai", "Karuppu", "Rosapoo", "Manal",
            "Thangam", "Velli", "Oodha", "Katralai", "Semman", "Pasumai", "Neer", "Kaatu",
            "Veyil", "Nilavu", "Mazhai", "Kadal", "Malai", "Thenral", "Sooriyan", "Chandiran",
            "Azhagu", "Veeram", "Amaidhi", "Uyarvu", "Thani", "Nalla", "Periya", "Chinna",
            "Puthiya", "Pazhaya", "Thelliya", "Iruul", "Oli", "Veppam", "Kulir", "Ilam",
            "Mutir", "Arumai", "Nirai", "Thuymai", "Vanmai", "Menmai", "Kovai", "Inbam"
    );

    private static final List<String> TA_NOUN = List.of(
            "Paravai", "Pul", "Yaanai", "Singam", "Kadal", "Malai", "Aaru", "Vaanam",
            "Deepam", "Chakram", "Thamarai", "Vajram", "Nakshathiram", "Nilavu", "Sooriyan", "Megham",
            "Kaattru", "Thee", "Thanneer", "Nilam", "Indiran", "Garudan", "Mayil", "Singam2",
            "Puli", "Yanai", "Kudhirai", "Gajam", "Uthayam", "Sandhya", "Paathai", "Kovil",
            "Kaadu", "Poo", "Kal", "Yathrikai", "Veeran", "Arasan", "Senai", "Thozhan",
            "Nanban", "Ulagam", "Vaanavil", "Kottai", "Thurai", "Paalam", "Thoni", "Kappal"
    );

    private final SecureRandom random = new SecureRandom();

    public String generate(ActiveChallenge.Language language, Set<String> usedInSession) {
        List<String> adj = adjectives(language);
        List<String> nouns = nouns(language);
        if (adj.size() < 40 || nouns.size() < 40) {
            throw new IllegalStateException("phrase lists must have >= 40 entries each");
        }
        for (int attempt = 0; attempt < 500; attempt++) {
            String a = adj.get(random.nextInt(adj.size()));
            String n = nouns.get(random.nextInt(nouns.size()));
            int num = 10 + random.nextInt(90); // 10–99 → 90 values
            String phrase = a + " " + n + " " + num;
            if (usedInSession.add(phrase)) {
                return phrase;
            }
        }
        // Extremely unlikely collision storm — append entropy.
        String fallback = adj.get(0) + " " + nouns.get(0) + " " + (10 + random.nextInt(90))
                + "-" + Integer.toHexString(random.nextInt());
        usedInSession.add(fallback);
        return fallback;
    }

    public ActiveChallenge.Language parseLanguage(String raw) {
        if (raw == null || raw.isBlank()) {
            return ActiveChallenge.Language.EN;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "HI", "HINDI", "HI-IN" -> ActiveChallenge.Language.HI;
            case "TA", "TAMIL", "TA-IN" -> ActiveChallenge.Language.TA;
            default -> ActiveChallenge.Language.EN;
        };
    }

    static int spaceSize() {
        return dedupe(EN_ADJ).size() * dedupe(EN_NOUN).size() * 90;
    }

    private static List<String> adjectives(ActiveChallenge.Language language) {
        return switch (language) {
            case HI -> HI_ADJ;
            case TA -> TA_ADJ;
            case EN -> dedupe(EN_ADJ);
        };
    }

    private static List<String> nouns(ActiveChallenge.Language language) {
        return switch (language) {
            case HI -> HI_NOUN;
            case TA -> TA_NOUN;
            case EN -> dedupe(EN_NOUN);
        };
    }

    private static List<String> dedupe(List<String> in) {
        return new ArrayList<>(Set.copyOf(in));
    }
}
