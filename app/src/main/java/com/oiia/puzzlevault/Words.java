package com.oiia.puzzlevault;

import java.util.Random;

final class Words {
    static final String[] LIST = {
            "PUZZLE", "KITTEN", "SPINNER", "GALAXY", "VORTEX", "WHISKER", "ROTATE",
            "CIRCLE", "PLANET", "MIRROR", "ORBIT", "CASTLE", "PHOENIX", "RIDDLE",
            "TORNADO", "LANTERN", "COMPASS", "DRAGON", "TWISTER", "MEOWING",
            "JUNGLE", "WIZARD", "PICKLE", "THUNDER", "FROZEN", "MONKEY"
    };

    static final String[] SHORT = {
            "CAT", "DOG", "SUN", "BOX", "KEY", "MAP", "OWL", "ZIP", "FOX", "JAM"
    };

    private Words() {}

    static String pick(Random rnd) {
        return LIST[rnd.nextInt(LIST.length)];
    }

    static String pickShort(Random rnd) {
        return SHORT[rnd.nextInt(SHORT.length)];
    }
}
