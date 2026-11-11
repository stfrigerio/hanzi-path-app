package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.util.TypedValue;

/**
 * The look: ink on paper. Rice paper, warm (night: ink on black lacquer, the paper's cream
 * for text); cards a shade lighter than the page with a soft edge; a line round what you can
 * touch; the main way on in vermilion, the seal's red; the highlighter, marigold, only under
 * what's next or suggested. The tones' inks: 1 blue, 2 green, 3 ochre, 4 red, neutral grey,
 * each readable on the paper (4.5:1). Every colour in the app is one of these.
 *
 * Faces: hanzi in text in Path Kai (fonts/han.ttf, LXGW WenKai cut by tools/cut-han), the
 * same hand as the strokes; labels, pinyin and figures in Android's serif; the rest in IBM
 * Plex Sans; the brush (Ma Shan Zheng) only for the logo and the seal.
 */
final class Ink {
    static int paper, paper2, ink, ink2, rule, hl, hlInk, known, young, grid, press;
    static int surface, edge, line, accent;   // a card's fill and edge, a touchable's line, the main way on
    static final int[] tone = new int[6];
    static boolean night;
    static Typeface serif, serifBold, sans, sansMedium, brush, han;
    static float density;

    private Ink() {}

    static void init(Context ctx) {
        density = ctx.getResources().getDisplayMetrics().density;
        night = (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            paper = 0xff1a1714; paper2 = 0xff231f1b; ink = 0xffede5d6; ink2 = 0xffaba190; rule = 0xff3b342c;
            tone[1] = 0xff8fb0ec; tone[2] = 0xff8ecf87; tone[3] = 0xffe9a462; tone[4] = 0xffef7b62; tone[5] = 0xff9a9083;
            known = 0xff8fb0ec; young = 0xffef7b62; grid = 0xff2a2520;
            surface = 0xff211d19; edge = 0xff3b342c; accent = 0xffe0603f; hl = 0xfff2cb45;
        } else {
            paper = 0xfff7f2e8; paper2 = 0xffefe8da; ink = 0xff2b2520; ink2 = 0xff6f665c; rule = 0xffdccfbb;
            tone[1] = 0xff2d5b9e; tone[2] = 0xff3a7535; tone[3] = 0xffa3561a; tone[4] = 0xffc0392b; tone[5] = 0xff746b60;
            known = 0xff2d5b9e; young = 0xffe58a72; grid = 0xffebe2d2;
            surface = 0xfffffdf8; edge = 0xffe2d6c2; accent = 0xffb5402a; hl = 0xffffd84a;
        }
        hlInk = night ? paper : ink;
        line = mix(edge, ink, 0.25f);
        press = mix(paper, hl, night ? 0.14f : 0.3f);     // paper under a finger: a wash of the highlighter
        if (sans == null) {
            serif = Typeface.create("serif", Typeface.NORMAL);                      // Android's Noto Serif
            serifBold = Typeface.create("serif", Typeface.BOLD);
            sans = Typeface.createFromAsset(ctx.getAssets(), "fonts/sans.ttf");
            sansMedium = Typeface.createFromAsset(ctx.getAssets(), "fonts/sans-medium.ttf");
            brush = Typeface.createFromAsset(ctx.getAssets(), "fonts/brush.ttf");   // 汉字之路好行练 only
            int id = ctx.getResources().getIdentifier("han", "font", ctx.getPackageName());
            han = id != 0 ? ctx.getResources().getFont(id) : Typeface.create("serif", Typeface.NORMAL);   // in res/font, for the widget too
        }
    }

    /** What a fill turns to under a finger: paper takes a wash of the highlighter, ink lifts
     *  towards the paper, any other ink deepens. */
    static int pressed(int fill) {
        if (fill == 0 || fill == paper || fill == paper2 || fill == surface) return press;
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
