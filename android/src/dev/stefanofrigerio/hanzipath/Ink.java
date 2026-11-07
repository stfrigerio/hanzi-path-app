package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.util.TypedValue;

/**
 * The look: a hypertext garden in the scrapbook look. White paper (night: its
 * navy), ink for text and the hairline round what you can touch, the highlighter only under
 * what's next or suggested. The tones are the garden's inks: 1 blue, 2 green, 3 peach,
 * 4 pink, neutral grey. Every colour in the app is one of these.
 */
final class Ink {
    static int paper, paper2, ink, ink2, rule, hl, hlInk, known, young, grid, press;
    static final int[] tone = new int[6];
    static boolean night;
    static Typeface mono, monoMedium, sans, sansMedium, brush, han;
    static float density;

    private Ink() {}

    static void init(Context ctx) {
        density = ctx.getResources().getDisplayMetrics().density;
        night = (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            paper = 0xff0e0f1a; paper2 = 0xff151626; ink = 0xffeeede6; ink2 = 0xffa9a8b8; rule = 0xff3a3b52;
            tone[1] = 0xff7aa2ff; tone[2] = 0xff7ee068; tone[3] = 0xffffb38a; tone[4] = 0xffff8cc0; tone[5] = 0xff84839a;
            known = 0xff7aa2ff; young = 0xffff8cc0; grid = 0xff24253a;
        } else {
            paper = 0xffffffff; paper2 = 0xfff7f6f0; ink = 0xff141414; ink2 = 0xff5c5c66; rule = 0xffc9c8c0;
            tone[1] = 0xff1f55c8; tone[2] = 0xff2e7d32; tone[3] = 0xffb5571c; tone[4] = 0xffc2286f; tone[5] = 0xff76767e;
            known = 0xff1f55c8; young = 0xfff27fae; grid = 0xffe3e2da;
        }
        hl = 0xffffff00;
        hlInk = 0xff141414;
        press = mix(paper, hl, night ? 0.14f : 0.3f);     // paper under a finger: a wash of the highlighter
        if (mono == null) {
            mono = Typeface.createFromAsset(ctx.getAssets(), "fonts/mono.ttf");
            monoMedium = Typeface.createFromAsset(ctx.getAssets(), "fonts/mono-medium.ttf");
            sans = Typeface.createFromAsset(ctx.getAssets(), "fonts/sans.ttf");
            sansMedium = Typeface.createFromAsset(ctx.getAssets(), "fonts/sans-medium.ttf");
            brush = Typeface.createFromAsset(ctx.getAssets(), "fonts/brush.ttf");   // 汉字之路好行练 only
            han = Typeface.create("serif", Typeface.NORMAL);                        // Android's Noto Serif CJK
        }
    }

    /** What a fill turns to under a finger: paper takes a wash of the highlighter, ink lifts
     *  towards the paper, any other ink deepens. */
    static int pressed(int fill) {
        if (fill == 0 || fill == paper || fill == paper2) return press;
        if (fill == ink) return mix(ink, paper, 0.22f);
        return mix(fill, hlInk, 0.14f);
    }

    /** a towards b by t, channel by channel (opaque). */
    static int mix(int a, int b, float t) {
        int r = 0xff000000;
        for (int sh = 0; sh <= 16; sh += 8) {
            int x = (a >> sh) & 0xff, y = (b >> sh) & 0xff;
            r |= Math.round(x + (y - x) * t) << sh;
        }
        return r;
    }

    static int dp(float v) { return Math.round(v * density); }

    static float px(Context ctx, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, ctx.getResources().getDisplayMetrics());
    }

    /** 1-4 from a syllable's tone mark, 5 for the neutral tone. */
    static int toneOf(String syl) {
        String[] marks = {"", "āēīōūǖĀĒĪŌŪǕ", "áéíóúǘÁÉÍÓÚǗ", "ǎěǐǒǔǚǍĚǏǑǓǙ", "àèìòùǜÀÈÌÒÙǛ"};
        for (int t = 1; t <= 4; t++)
            for (int i = 0; i < syl.length(); i++) if (marks[t].indexOf(syl.charAt(i)) >= 0) return t;
        return 5;
    }
}
