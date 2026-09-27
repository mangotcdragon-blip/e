package com.oiia.puzzlevault;

import java.util.Random;

final class Words {
    static final String[] LIST = {
            "PUZZLE", "KITTEN", "SPINNER", "GALAXY", "VORTEX", "WHISKER", "ROTATE",
            "CIRCLE", "PLANET", "MIRROR", "ORBIT", "CASTLE", "PHOENIX", "RIDDLE",
            "TORNADO", "LANTERN", "COMPASS", "DRAGON", "TWISTER", "MEOWING"
    };

    private Words() {}

    static String pick(Random rnd) {
        return LIST[rnd.nextInt(LIST.length)];
    }
}
