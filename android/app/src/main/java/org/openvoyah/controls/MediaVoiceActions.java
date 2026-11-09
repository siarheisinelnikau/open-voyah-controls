package org.openvoyah.controls;
import android.content.*;
import android.media.*;
import android.media.session.MediaSessionManager;
import android.view.KeyEvent;
import java.util.Set;

final class MediaVoiceActions implements LocalVoiceAction {
    private final Context context;
    MediaVoiceActions(Context context) { this.context=context; }
    @Override public boolean supports(String id) { return Set.of("Like","RemoveLike","DisLike","MediaPlay","MediaPause","MediaNext","MediaPrevious","VolumeUp","VolumeDown","VolumeMute").contains(id); }
    @Override public String execute(String id) {
        AudioManager audio=context.getSystemService(AudioManager.class);
        if(id.equals("Like") || id.equals("RemoveLike") || id.equals("DisLike")) {
            try {
                var sessions=context.getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(context,MediaAccessService.class));
                if(sessions.isEmpty())throw new IllegalStateException(UiText.get(R.string.ui_135));
                var controller=sessions.get(0);
                int style=controller.getRatingType();
                Rating rating=switch(style) {
                    case Rating.RATING_HEART -> Rating.newHeartRating(id.equals("Like"));
                    case Rating.RATING_THUMB_UP_DOWN -> id.equals("RemoveLike")
                        ? Rating.newUnratedRating(style) : Rating.newThumbRating(id.equals("Like"));
                    default -> throw new IllegalStateException(UiText.get(R.string.ui_132));
                };
                if(style==Rating.RATING_HEART && id.equals("DisLike")) {
                    throw new IllegalStateException(UiText.get(R.string.ui_131));
                }
                controller.getTransportControls().setRating(rating);
                return UiText.get(R.string.ui_133);
            } catch(SecurityException ex) { throw new IllegalStateException(UiText.get(R.string.ui_134),ex); }
        }
        int key=switch(id) {
            case "MediaPlay"->KeyEvent.KEYCODE_MEDIA_PLAY;case "MediaPause"->KeyEvent.KEYCODE_MEDIA_PAUSE;
            case "MediaNext"->KeyEvent.KEYCODE_MEDIA_NEXT;case "MediaPrevious"->KeyEvent.KEYCODE_MEDIA_PREVIOUS;default->0;
        };
        if(key!=0) {
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,key));audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,key));return UiText.get(R.string.ui_136);
        }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,id.equals("VolumeUp")?AudioManager.ADJUST_RAISE:id.equals("VolumeDown")?AudioManager.ADJUST_LOWER:AudioManager.ADJUST_MUTE,AudioManager.FLAG_SHOW_UI);
        return UiText.get(R.string.ui_137);
    }
}
