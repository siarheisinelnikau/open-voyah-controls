package app.voyahradio.ui;

import app.voyahradio.R;
import app.voyahradio.domain.RadioTab;

final class RadioLabels {
    private RadioLabels() {}

    static int tab(RadioTab tab) {
        return switch (tab) {
            case BELARUS -> R.string.belarus;
            case RUSSIA -> R.string.russia;
            case INTERNATIONAL -> R.string.international;
            case FAVORITES -> R.string.favorites;
        };
    }
}
