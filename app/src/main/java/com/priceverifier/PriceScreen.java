package com.priceverifier;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Draws the price check shown after a scan. Two settings decide the look: the
 * layout (where the name, barcode and price sit) and the promo style (how an
 * active promo is drawn). Every layout works with every promo style.
 *
 * <p>Sizes come from the panel's own size, measured against a 1280x800
 * reference screen, so the same code draws the full-screen panel and the small
 * previews in Admin. Prices and promo numbers are measured and shrunk to fit
 * their space, so a long price never runs off the screen.
 */
final class PriceScreen {
    static final String LAYOUT_CENTER = "center";
    static final String LAYOUT_LEFT = "left";
    static final String LAYOUT_SPLIT = "split";
    static final String LAYOUT_PRICE_FIRST = "price_first";
    static final List<String> LAYOUTS = Arrays.asList(
            LAYOUT_CENTER, LAYOUT_LEFT, LAYOUT_SPLIT, LAYOUT_PRICE_FIRST);

    static final String PROMO_CLASSIC = "classic";
    static final String PROMO_STICKER = "sticker";
    static final String PROMO_RIBBON = "ribbon";
    static final String PROMO_POSTER = "poster";
    static final String PROMO_TAG = "tag";
    static final List<String> PROMO_STYLES = Arrays.asList(
            PROMO_CLASSIC, PROMO_STICKER, PROMO_RIBBON, PROMO_POSTER, PROMO_TAG);

    private static final float REF_WIDTH = 1280f;
    private static final float REF_HEIGHT = 800f;
    /** The light card behind the price (Split) and the name band (Price first). */
    private static final int CARD_COLOR = 0x99FFFFFF;
    private static final int SOFT_CHIP_COLOR = 0xB3FFFFFF;
    private static final int DARK_CHIP_COLOR = 0xFF0F172A;
    private static final int OUTLINE_CHIP_COLOR = 0x590F172A;
    private static final int CHIP_TEXT_COLOR = 0xFF1E293B;
    private static final int RING_COLOR = 0x8CFFFFFF;
    private static final String MINUS = String.valueOf((char) 0x2212);

    /** The four colours set in Admin or the portal's Design tab. */
    static final class Colors {
        final int background;
        final int name;
        final int meta;
        final int price;

        Colors(int background, int name, int meta, int price) {
            this.background = background;
            this.name = name;
            this.meta = meta;
            this.price = price;
        }

        static Colors from(AppSettings settings) {
            return new Colors(settings.getCheckBackgroundColor(), settings.getCheckNameColor(),
                    settings.getCheckMetaColor(), settings.getCheckPriceColor());
        }
    }

    /** What one scan shows; {@code deal} is null when no promo applies. */
    static final class Content {
        final String name;
        final String meta;
        final String price;
        final Deal deal;

        Content(String name, String meta, String price, Deal deal) {
            this.name = name;
            this.meta = meta;
            this.price = price;
            this.deal = deal;
        }
    }

    /** The best active promo for a scan, reduced to the words the styles draw. */
    static final class Deal {
        final String label;
        final String shortLabel;
        /** The regular price, struck through; empty for a Buy X Take Y on its own. */
        final String was;
        final String saved;
        final String save;
        /** The two lines of the sticker and poster: "50%" / "OFF", "SAVE" / "₱8.25". */
        final String bigTop;
        final String bigBottom;
        final boolean topIsBig;
        final String badge;
        /** "+ BUY 1 TAKE 1" when a Buy X Take Y rides along with a price promo. */
        final String extra;
        final String until;
        final String untilDate;
        final String ends;
        final String bannerTitle;
        final String bannerDetail;
        final int color;
        final int ink;

        private Deal(String label, String shortLabel, String was, String saved, String save,
                     String bigTop, String bigBottom, boolean topIsBig, String badge, String extra,
                     String until, String untilDate, String ends, String bannerTitle,
                     String bannerDetail, int color, int ink) {
            this.label = label;
            this.shortLabel = shortLabel;
            this.was = was;
            this.saved = saved;
            this.save = save;
            this.bigTop = bigTop;
            this.bigBottom = bigBottom;
            this.topIsBig = topIsBig;
            this.badge = badge;
            this.extra = extra;
            this.until = until;
            this.untilDate = untilDate;
            this.ends = ends;
            this.bannerTitle = bannerTitle;
            this.bannerDetail = bannerDetail;
            this.color = color;
            this.ink = ink;
        }

        /** Null when {@code best} holds no promo. */
        static Deal from(Context context, Promo.Best best, long regularCents) {
            if (best == null || best.isEmpty()) {
                return null;
            }
            Promo lead = best.priced != null ? best.priced : best.buyTake;
            boolean priced = best.priced != null;
            long savedCents = priced ? regularCents - best.priceCents : 0L;
            String saved = savedCents > 0 ? PriceFormatter.format(savedCents) : "";

            String bigTop;
            String bigBottom;
            boolean topIsBig = true;
            String badge;
            switch (lead.type) {
                case PERCENT:
                    bigTop = Promo.formatPercent(lead.value) + "%";
                    bigBottom = "OFF";
                    badge = MINUS + bigTop;
                    break;
                case AMOUNT:
                    bigTop = PriceFormatter.format(lead.value);
                    bigBottom = "OFF";
                    badge = MINUS + bigTop;
                    break;
                case FIXED:
                    bigTop = "SAVE";
                    bigBottom = saved;
                    topIsBig = false;
                    badge = saved.isEmpty() ? "" : MINUS + saved;
                    break;
                default:
                    bigTop = "BUY " + lead.buyQty;
                    bigBottom = "TAKE " + lead.takeQty;
                    topIsBig = false;
                    badge = lead.buyQty + "+" + lead.takeQty;
                    break;
            }

            String extra = priced && best.buyTake != null
                    ? "+ " + best.buyTake.dealText(regularCents) : "";
            String untilDate = lead.endDate.isEmpty() ? "" : Promo.displayDate(lead.endDate);
            String until = untilDate.isEmpty() ? ""
                    : context.getString(R.string.promo_until, untilDate);

            // The classic banner, worded as it always was.
            String deal = lead.dealText(regularCents);
            String bannerTitle = deal.isEmpty() ? lead.labelText()
                    : lead.labelText() + Promo.DOT + deal;
            StringBuilder detail = new StringBuilder();
            if (!extra.isEmpty()) {
                detail.append(extra);
            }
            if (!until.isEmpty()) {
                if (detail.length() > 0) {
                    detail.append(Promo.DOT);
                }
                detail.append(until);
            }

            boolean special = lead.isSpecial();
            return new Deal(lead.labelText(), special ? "SPECIAL" : "PROMO",
                    priced ? PriceFormatter.format(regularCents) : "", saved,
                    saved.isEmpty() ? "" : context.getString(R.string.promo_save, saved),
                    bigTop, bigBottom, topIsBig, badge, extra, until, untilDate,
                    endsText(context, lead.endDate), bannerTitle, detail.toString(),
                    special ? 0xFF2563EB : 0xFFDC2626, special ? 0xFF1D4ED8 : 0xFFB91C1C);
        }

        /** "Ends today", "Ends tomorrow", "Ends in 9 days"; empty with no end date. */
        private static String endsText(Context context, String endDate) {
            if (endDate.isEmpty()) {
                return "";
            }
            try {
                SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                long end = iso.parse(endDate).getTime();
                long today = iso.parse(Promo.today()).getTime();
                // Rounded, so a daylight-saving change in between still counts whole days.
                long days = Math.round((end - today) / 86_400_000.0);
                if (days <= 0) {
                    return context.getString(R.string.promo_ends_today);
                }
                if (days == 1) {
                    return context.getString(R.string.promo_ends_tomorrow);
                }
                return context.getString(R.string.promo_ends_in, (int) days);
            } catch (ParseException | NullPointerException ex) {
                return "";
            }
        }
    }

    private final Context context;
    private final int width;
    private final int height;
    private final float scale;
    private final String layout;
    /** The promo style, or null when no promo applies. */
    private final String style;
    private final Colors colors;
    private final Content content;
    private final Deal deal;
    private View bar;

    private PriceScreen(Context context, int width, int height, String layout, String promoStyle,
                        Colors colors, Content content) {
        this.context = context;
        this.width = width;
        this.height = height;
        this.scale = Math.min(width / REF_WIDTH, height / REF_HEIGHT);
        // A tall screen has no room for two columns.
        this.layout = LAYOUT_SPLIT.equals(layout) && height > width ? LAYOUT_LEFT : layout;
        this.deal = content.deal;
        this.style = deal == null ? null : promoStyle;
        this.colors = colors;
        this.content = content;
    }

    /**
     * Replaces what {@code panel} shows with the price check for {@code content},
     * drawn for a panel of {@code width} x {@code height} pixels. Returns the bar
     * that blinks under the price, or null when the chosen design has none.
     */
    static View render(FrameLayout panel, int width, int height, String layout, String promoStyle,
                       Colors colors, Content content) {
        panel.removeAllViews();
        panel.setBackgroundColor(colors.background);
        if (width <= 0 || height <= 0) {
            return null;
        }
        return new PriceScreen(panel.getContext(), width, height, layout, promoStyle, colors,
                content).build(panel);
    }

    private View build(FrameLayout panel) {
        if (PROMO_POSTER.equals(style)) {
            // The poster block takes the right third; the layout fills the rest,
            // a Split as Left aligned since there is no room for its card.
            int blockWidth = Math.round(width * 0.34f);
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            FrameLayout area = new FrameLayout(context);
            buildLayout(area, LAYOUT_SPLIT.equals(layout) ? LAYOUT_LEFT : layout,
                    width - blockWidth);
            row.addView(area, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            row.addView(posterBlock(blockWidth), new LinearLayout.LayoutParams(blockWidth,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            panel.addView(row, matchParent());
        } else {
            buildLayout(panel, layout, width);
        }
        if (PROMO_STICKER.equals(style)) {
            addSticker(panel);
        } else if (PROMO_RIBBON.equals(style)) {
            addRibbon(panel);
        }
        return bar;
    }

    private void buildLayout(FrameLayout area, String which, int areaWidth) {
        switch (which) {
            case LAYOUT_LEFT:
                stacked(area, areaWidth, false);
                break;
            case LAYOUT_SPLIT:
                split(area, areaWidth);
                break;
            case LAYOUT_PRICE_FIRST:
                priceFirst(area, areaWidth);
                break;
            default:
                stacked(area, areaWidth, true);
                break;
        }
    }

    // --- Layouts ----------------------------------------------------------

    /** Centered (the original screen) and Left aligned: one column of text. */
    private void stacked(FrameLayout area, int areaWidth, boolean centered) {
        boolean classic = PROMO_CLASSIC.equals(style);
        int side = px(centered ? 52 : 104);
        int endReserve = centered ? 0 : decorationReserve();
        LinearLayout column = column((centered ? Gravity.CENTER_HORIZONTAL : Gravity.START)
                | Gravity.CENTER_VERTICAL);
        column.setPadding(side, px(32), side + endReserve, px(32) + (classic ? px(150) : 0));
        float textWidth = areaWidth - 2f * side - endReserve;

        TextView name = nameView(centered ? 64 : 68, centered);
        LinearLayout.LayoutParams nameParams = wide(0);
        if (centered) {
            // Kept clear of a sticker or ribbon in the corner, on both sides so it stays centred.
            nameParams.leftMargin = decorationReserve();
            nameParams.rightMargin = decorationReserve();
        }
        column.addView(name, nameParams);
        column.addView(metaView(centered), wide(px(centered ? 22 : 20)));
        addPriceBlock(column, textWidth, centered ? 168 : 196, centered, true);
        addExtras(column, textWidth, centered);
        area.addView(column, matchParent());

        if (classic) {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM);
            params.setMargins(px(52), 0, px(52), px(52));
            area.addView(banner(centered ? BANNER_CENTER : BANNER_ROW), params);
        }
    }

    /** Split: the details on the left, the price on a light card on the right. */
    private void split(FrameLayout area, int areaWidth) {
        int pad = px(40);
        int cardWidth = Math.round(areaWidth * 0.42f);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(pad, pad, pad, pad);
        row.setClipChildren(false);
        row.setClipToPadding(false);

        LinearLayout details = column(Gravity.START | Gravity.CENTER_VERTICAL);
        details.setPadding(px(56), 0, 0, 0);
        float detailsWidth = areaWidth - 3f * pad - cardWidth - px(56);
        details.addView(nameView(60, false), wide(0));
        details.addView(metaView(false), wide(px(22)));
        if (PROMO_CLASSIC.equals(style)) {
            details.addView(banner(BANNER_BLOCK_START), wrap(px(48)));
        } else {
            addExtras(details, detailsWidth, false);
        }
        row.addView(details, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout card = column(Gravity.CENTER);
        card.setBackground(rounded(CARD_COLOR, px(32)));
        int cardPad = px(28);
        card.setPadding(cardPad, cardPad, cardPad, cardPad);
        addPriceBlock(card, cardWidth - 2f * cardPad, 136, true, true);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(cardWidth,
                ViewGroup.LayoutParams.MATCH_PARENT);
        cardParams.leftMargin = pad;
        row.addView(card, cardParams);
        area.addView(row, matchParent());
    }

    /** Price first: a very large price on top, the name in a band along the bottom. */
    private void priceFirst(FrameLayout area, int areaWidth) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);

        int side = px(104);
        int endReserve = decorationReserve();
        LinearLayout top = column(Gravity.START | Gravity.CENTER_VERTICAL);
        top.setPadding(side, px(24), side + endReserve, px(24));
        float topWidth = areaWidth - 2f * side - endReserve;
        addPriceBlock(top, topWidth, 236, false, false);
        addExtras(top, topWidth, false);
        column.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout band = new LinearLayout(context);
        band.setOrientation(LinearLayout.HORIZONTAL);
        band.setGravity(Gravity.CENTER_VERTICAL);
        band.setBackgroundColor(CARD_COLOR);
        band.setPadding(side, 0, side, 0);
        LinearLayout info = column(Gravity.START | Gravity.CENTER_VERTICAL);
        info.addView(nameView(56, false), wide(0));
        info.addView(metaView(false), wide(px(16)));
        band.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (PROMO_CLASSIC.equals(style)) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.leftMargin = px(40);
            band.addView(banner(BANNER_BLOCK_END), params);
        }
        column.addView(band, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(232)));
        area.addView(column, matchParent());
    }

    // --- The price and what goes with it -----------------------------------

    /**
     * The price as the promo style shows it: plain (with the regular price
     * struck through above it under a promo), beside a discount pill (Corner
     * ribbon) or on a shelf tag (Price tag).
     */
    private void addPriceBlock(LinearLayout column, float available, float refSize,
                               boolean centered, boolean withBar) {
        int gravity = centered ? Gravity.CENTER_HORIZONTAL : Gravity.START;
        if (PROMO_RIBBON.equals(style)) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setClipChildren(false);
            LinearLayout side = column(Gravity.START);
            if (!deal.was.isEmpty()) {
                side.addView(wasView(44), wrap(0));
            }
            if (!deal.badge.isEmpty()) {
                TextView pill = text(deal.badge, Typeface.create("sans-serif-black", Typeface.NORMAL),
                        fpx(44), 0xFFFFFFFF);
                pill.setBackground(rounded(deal.color, px(14)));
                pill.setPadding(px(18), px(4), px(18), px(4));
                side.addView(pill, wrap(px(14)));
            }
            float sideWidth = Math.max(measure(side), fpx(170));
            TextPaint paint = new TextPaint();
            paint.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
            paint.setTextSize(fpx(refSize * 0.92f));
            if (paint.measureText(content.price) + sideWidth + px(36) > available) {
                // No room beside the price (the Split card): the regular price
                // and the pill go in a row under it.
                side.setOrientation(LinearLayout.HORIZONTAL);
                side.setGravity(Gravity.CENTER_VERTICAL);
                View pill = side.getChildCount() > 1 ? side.getChildAt(1) : null;
                if (pill != null) {
                    LinearLayout.LayoutParams pillParams = wrapRow();
                    pillParams.leftMargin = px(18);
                    pill.setLayoutParams(pillParams);
                }
                column.addView(priceView(refSize * 0.92f, available), wrapGravity(px(40), gravity));
                column.addView(side, wrapGravity(px(18), gravity));
                return;
            }
            row.addView(priceView(refSize * 0.92f, available - sideWidth - px(36)), wrapRow());
            LinearLayout.LayoutParams sideParams = wrapRow();
            sideParams.leftMargin = px(36);
            row.addView(side, sideParams);
            column.addView(row, wrapGravity(px(40), gravity));
            return;
        }
        if (PROMO_TAG.equals(style)) {
            if (!deal.was.isEmpty()) {
                LinearLayout wasRow = new LinearLayout(context);
                wasRow.setOrientation(LinearLayout.HORIZONTAL);
                wasRow.setGravity(Gravity.BOTTOM);
                TextView label = text(context.getString(R.string.promo_was), Typeface.DEFAULT_BOLD,
                        fpx(24), colors.meta);
                label.setLetterSpacing(0.16f);
                wasRow.addView(label, wrapRow());
                LinearLayout.LayoutParams wasParams = wrapRow();
                wasParams.leftMargin = px(14);
                wasRow.addView(wasView(48), wasParams);
                column.addView(wasRow, wrapGravity(px(36), gravity));
            }
            column.addView(tag(refSize * 0.82f, available), wrapGravity(px(26), gravity));
            return;
        }

        boolean struck = deal != null && !deal.was.isEmpty();
        if (struck) {
            column.addView(wasView(52), wrapGravity(px(36), gravity));
        }
        TextView price = priceView(refSize, available);
        price.setGravity(gravity);
        column.addView(price, wrapGravity(px(struck ? 6 : 48), gravity));
        // The blinking bar marks a fresh scan; the promo styles that add their
        // own shapes leave it out.
        if (withBar && (style == null || PROMO_CLASSIC.equals(style))) {
            bar = new View(context);
            bar.setBackgroundColor(colors.price);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    px(centered ? 66 : 96), Math.max(1, px(centered ? 4 : 6)));
            params.topMargin = px(40);
            params.gravity = gravity;
            column.addView(bar, params);
        }
    }

    /** The chips under the price: what is saved, a Buy X Take Y, the end date. */
    private void addExtras(LinearLayout column, float available, boolean centered) {
        if (style == null) {
            return;
        }
        List<TextView> chips = new ArrayList<>();
        switch (style) {
            case PROMO_STICKER:
                addIfText(chips, chip(deal.save, deal.color, 0, 0xFFFFFFFF, true));
                addIfText(chips, chip(deal.extra, DARK_CHIP_COLOR, 0, 0xFFFFFFFF, true));
                addIfText(chips, chip(deal.until, 0, OUTLINE_CHIP_COLOR, CHIP_TEXT_COLOR, false));
                break;
            case PROMO_RIBBON:
                addIfText(chips, chip(deal.save, SOFT_CHIP_COLOR, 0, deal.ink, true));
                addIfText(chips, chip(deal.extra, DARK_CHIP_COLOR, 0, 0xFFFFFFFF, true));
                addIfText(chips, chip(deal.ends, SOFT_CHIP_COLOR, 0, CHIP_TEXT_COLOR, false));
                break;
            case PROMO_TAG:
                TextView label = chip(deal.label, 0, deal.color, deal.ink, true);
                label.setLetterSpacing(0.16f);
                chips.add(label);
                addIfText(chips, plain(deal.save, Typeface.DEFAULT_BOLD, 30, CHIP_TEXT_COLOR));
                addIfText(chips, chip(deal.extra, DARK_CHIP_COLOR, 0, 0xFFFFFFFF, true));
                addIfText(chips, plain(deal.ends.isEmpty() ? "" : (char) 0x00B7 + " " + deal.ends,
                        Typeface.create("sans-serif-medium", Typeface.NORMAL), 26, colors.meta));
                break;
            case PROMO_POSTER:
                if (!deal.saved.isEmpty()) {
                    LinearLayout line = new LinearLayout(context);
                    line.setOrientation(LinearLayout.HORIZONTAL);
                    line.setGravity(Gravity.CENTER_VERTICAL);
                    View dot = new View(context);
                    dot.setBackground(oval(deal.color));
                    line.addView(dot, new LinearLayout.LayoutParams(px(14), px(14)));
                    TextView saved = plain(context.getString(R.string.promo_you_save, deal.saved),
                            Typeface.DEFAULT_BOLD, 32, deal.ink);
                    LinearLayout.LayoutParams params = wrapRow();
                    params.leftMargin = px(14);
                    line.addView(saved, params);
                    column.addView(line, wrapGravity(px(30),
                            centered ? Gravity.CENTER_HORIZONTAL : Gravity.START));
                }
                return;
            default:
                return;
        }
        addChipRows(column, chips, available, centered, px(PROMO_TAG.equals(style) ? 44 : 34));
    }

    /** Lays the chips out in as many rows as their widths need. */
    private void addChipRows(LinearLayout column, List<TextView> chips, float available,
                             boolean centered, int topMargin) {
        int gap = px(14);
        LinearLayout row = null;
        float used = 0f;
        for (TextView chip : chips) {
            float chipWidth = chip.getPaint().measureText(chip.getText().toString())
                    + chip.getPaddingLeft() + chip.getPaddingRight();
            if (row == null || used + gap + chipWidth > available) {
                boolean first = row == null;
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                column.addView(row, wrapGravity(first ? topMargin : px(12),
                        centered ? Gravity.CENTER_HORIZONTAL : Gravity.START));
                used = chipWidth;
                row.addView(chip, wrapRow());
            } else {
                LinearLayout.LayoutParams params = wrapRow();
                params.leftMargin = gap;
                row.addView(chip, params);
                used += gap + chipWidth;
            }
        }
    }

    // --- Promo styles -------------------------------------------------------

    private static final int BANNER_CENTER = 0;
    private static final int BANNER_ROW = 1;
    private static final int BANNER_BLOCK_START = 2;
    private static final int BANNER_BLOCK_END = 3;

    /** Classic: the original promo banner, full width or as a block. */
    private View banner(int shape) {
        boolean row = shape == BANNER_ROW;
        boolean block = shape == BANNER_BLOCK_START || shape == BANNER_BLOCK_END;
        LinearLayout banner = new LinearLayout(context);
        banner.setOrientation(row ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        banner.setBackground(rounded(deal.color, px(block ? 16 : 18)));
        int gravity = shape == BANNER_BLOCK_START ? Gravity.START
                : shape == BANNER_BLOCK_END ? Gravity.END : Gravity.CENTER_HORIZONTAL;
        banner.setGravity(row ? Gravity.CENTER_VERTICAL : gravity);
        banner.setPadding(px(row ? 52 : 28), px(20), px(row ? 52 : 28), px(20));

        TextView title = text(deal.bannerTitle, Typeface.create("sans-serif-black", Typeface.NORMAL),
                fpx(block ? 34 : 40), 0xFFFFFFFF);
        title.setLetterSpacing(0.06f);
        TextView detail = text(deal.bannerDetail,
                Typeface.create("sans-serif-medium", Typeface.NORMAL), fpx(24), 0xFFFFFFFF);
        detail.setAlpha(0.92f);
        if (row) {
            banner.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (!deal.bannerDetail.isEmpty()) {
                LinearLayout.LayoutParams params = wrapRow();
                params.leftMargin = px(24);
                banner.addView(detail, params);
            }
        } else {
            banner.addView(title, wrap(0));
            if (!deal.bannerDetail.isEmpty()) {
                banner.addView(detail, wrap(px(6)));
            }
        }
        return banner;
    }

    /** Sticker: a tilted round badge with the deal in big type. */
    private void addSticker(FrameLayout panel) {
        float size;
        float top;
        float end;
        switch (layout) {
            case LAYOUT_LEFT:
                size = height * 0.40f;
                top = height * 0.19f;
                end = width * 0.086f;
                break;
            case LAYOUT_SPLIT:
                size = height * 0.28f;
                top = height * 0.03f;
                end = width * 0.02f;
                break;
            case LAYOUT_PRICE_FIRST:
                size = height * 0.40f;
                top = height * 0.06f;
                end = width * 0.05f;
                break;
            default:
                size = height * 0.30f;
                top = height * 0.05f;
                end = width * 0.04f;
                break;
        }
        size = Math.min(size, width * 0.25f);
        int d = Math.round(size);

        FrameLayout sticker = new FrameLayout(context);
        sticker.setBackground(oval(deal.color));
        sticker.setElevation(fpx(14));
        sticker.setRotation(-12f);

        LinearLayout ring = column(Gravity.CENTER);
        GradientDrawable ringShape = new GradientDrawable();
        ringShape.setShape(GradientDrawable.OVAL);
        ringShape.setStroke(Math.max(1, Math.round(size * 0.0125f)), RING_COLOR,
                size * 0.03f, size * 0.025f);
        ring.setBackground(ringShape);
        float inner = size * 0.68f;
        TextView label = text(deal.shortLabel, Typeface.DEFAULT_BOLD, size * 0.069f, 0xFFFFFFFF);
        label.setLetterSpacing(0.25f);
        ring.addView(label, wrap(0));
        Typeface black = Typeface.create("sans-serif-black", Typeface.NORMAL);
        ring.addView(fitted(deal.bigTop, black, size * (deal.topIsBig ? 0.35f : 0.15f), inner,
                0xFFFFFFFF), wrap(Math.round(size * 0.012f)));
        ring.addView(fitted(deal.bigBottom, black, size * (deal.topIsBig ? 0.16f : 0.24f), inner,
                0xFFFFFFFF), wrap(0));
        int ringSize = Math.round(size * 0.89f);
        sticker.addView(ring, new FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(d, d, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(top);
        params.rightMargin = Math.round(end);
        panel.addView(sticker, params);
    }

    /** Corner ribbon: a diagonal band across the top-right corner. */
    private void addRibbon(FrameLayout panel) {
        int bandHeight = px(80);
        int length = bandHeight * 7;
        int corner = Math.round(bandHeight * 3.5f);
        TextView ribbon = text(deal.label, Typeface.create("sans-serif-black", Typeface.NORMAL),
                fpx(28), 0xFFFFFFFF);
        ribbon.setLetterSpacing(0.28f);
        // Only the stretch between the two screen edges shows, less the corners
        // the edges cut off at 45 degrees; "SPECIAL PRICE" is shrunk to fit it.
        fit(ribbon, deal.label, fpx(28), corner * 1.414f - bandHeight * 1.6f);
        ribbon.setGravity(Gravity.CENTER);
        ribbon.setBackgroundColor(deal.color);
        ribbon.setElevation(fpx(10));
        ribbon.setRotation(45f);
        // Placed at the top-right, then moved so its centre line runs from
        // `corner` pixels left of the corner to `corner` pixels below it.
        ribbon.setTranslationX(length / 2f - corner / 2f);
        ribbon.setTranslationY(corner / 2f - bandHeight / 2f);
        panel.addView(ribbon, new FrameLayout.LayoutParams(length, bandHeight, Gravity.TOP | Gravity.END));
    }

    /** Poster block: the right third in the promo colour, the deal in oversized type. */
    private View posterBlock(int blockWidth) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setBackgroundColor(deal.color);
        int side = px(44);
        block.setPadding(side, px(56), side, px(52));
        float inner = blockWidth - 2f * side;

        TextView label = text(deal.label, Typeface.DEFAULT_BOLD, fpx(24), 0xFFFFFFFF);
        label.setLetterSpacing(0.33f);
        block.addView(label, wrap(0));
        View rule = new View(context);
        rule.setBackgroundColor(0xFFFFFFFF);
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(px(56), Math.max(1, px(5)));
        ruleParams.topMargin = px(16);
        block.addView(rule, ruleParams);

        block.addView(new Space(context), new LinearLayout.LayoutParams(1, 0, 1f));
        Typeface black = Typeface.create("sans-serif-black", Typeface.NORMAL);
        TextView top = fitted(deal.bigTop, black, fpx(deal.topIsBig ? 176 : 76), inner, 0xFFFFFFFF);
        top.setIncludeFontPadding(false);
        block.addView(top, wrap(0));
        TextView bottom = fitted(deal.bigBottom, black, fpx(deal.topIsBig ? 104 : 112), inner,
                0xFFFFFFFF);
        bottom.setIncludeFontPadding(false);
        block.addView(bottom, wrap(px(4)));
        if (!deal.extra.isEmpty()) {
            TextView extra = fitted(deal.extra, Typeface.DEFAULT_BOLD, fpx(26), inner - px(42),
                    0xFFFFFFFF);
            GradientDrawable box = rounded(0, px(12));
            box.setStroke(Math.max(1, px(3)), 0xFFFFFFFF);
            extra.setBackground(box);
            extra.setPadding(px(18), px(10), px(18), px(10));
            block.addView(extra, wrap(px(22)));
        }
        block.addView(new Space(context), new LinearLayout.LayoutParams(1, 0, 1f));

        if (!deal.untilDate.isEmpty()) {
            TextView untilLabel = text(context.getString(R.string.promo_until_label),
                    Typeface.DEFAULT_BOLD, fpx(18), 0xFFFFFFFF);
            untilLabel.setLetterSpacing(0.33f);
            untilLabel.setAlpha(0.85f);
            block.addView(untilLabel, wrap(0));
            block.addView(fitted(deal.untilDate, Typeface.DEFAULT_BOLD, fpx(36), inner, 0xFFFFFFFF),
                    wrap(px(6)));
        }
        return block;
    }

    /** Price tag: the price in white on a tilted tag with a punched hole and a round badge. */
    private View tag(float refSize, float available) {
        FrameLayout tag = new FrameLayout(context);
        tag.setClipChildren(false);
        tag.setRotation(-3f);

        TextView price = new TextView(context);
        price.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        price.setLetterSpacing(-0.02f);
        price.setIncludeFontPadding(false);
        price.setSingleLine(true);
        price.setTextColor(0xFFFFFFFF);
        // The tag's padding grows with the type (1.05 x the size in all), so the
        // text and the padding are fitted together.
        float maxSize = fpx(refSize);
        TextPaint paint = new TextPaint(price.getPaint());
        paint.setTextSize(maxSize);
        float perPixel = paint.measureText(content.price) / maxSize;
        float size = Math.min(maxSize, available / (perPixel + 1.05f));
        price.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        price.setText(content.price);
        price.setPadding(Math.round(size * 0.65f), Math.round(size * 0.16f),
                Math.round(size * 0.40f), Math.round(size * 0.16f));
        price.setBackground(rounded(deal.color, size * 0.175f));
        price.setElevation(fpx(16));
        tag.addView(price, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        View hole = new View(context);
        hole.setBackground(oval(colors.background));
        hole.setElevation(fpx(17));
        int holeSize = Math.round(size * 0.19f);
        FrameLayout.LayoutParams holeParams = new FrameLayout.LayoutParams(holeSize, holeSize,
                Gravity.START | Gravity.CENTER_VERTICAL);
        holeParams.leftMargin = Math.round(size * 0.24f);
        tag.addView(hole, holeParams);

        if (!deal.badge.isEmpty()) {
            float badgeSize = size * 0.85f;
            TextView badge = fitted(deal.badge, Typeface.create("sans-serif-black", Typeface.NORMAL),
                    badgeSize * 0.30f, badgeSize * 0.78f, deal.ink);
            badge.setGravity(Gravity.CENTER);
            GradientDrawable circle = oval(0xFFFFFFFF);
            circle.setStroke(Math.max(1, Math.round(badgeSize * 0.045f)), deal.color);
            badge.setBackground(circle);
            badge.setElevation(fpx(18));
            badge.setRotation(14f);
            badge.setTranslationX(badgeSize * 0.4f);
            badge.setTranslationY(-badgeSize * 0.4f);
            int b = Math.round(badgeSize);
            tag.addView(badge, new FrameLayout.LayoutParams(b, b, Gravity.TOP | Gravity.END));
        }
        return tag;
    }

    /**
     * Room kept free at the top-right for a sticker or ribbon, so a long product
     * name does not run under it.
     */
    private int decorationReserve() {
        if (PROMO_RIBBON.equals(style)) {
            return px(210);
        }
        if (PROMO_STICKER.equals(style)) {
            switch (layout) {
                case LAYOUT_LEFT:
                    return Math.round(Math.min(height * 0.40f, width * 0.25f) + width * 0.086f) + px(24)
                            - px(104);
                case LAYOUT_PRICE_FIRST:
                    return Math.round(Math.min(height * 0.40f, width * 0.25f) + width * 0.05f) + px(24)
                            - px(104);
                case LAYOUT_CENTER:
                    return Math.round(Math.min(height * 0.30f, width * 0.25f) + width * 0.04f) + px(24)
                            - px(52);
                default:
                    return 0;
            }
        }
        return 0;
    }

    // --- Text ---------------------------------------------------------------

    private TextView nameView(float refSize, boolean centered) {
        TextView name = text(content.name, Typeface.create("sans-serif-medium", Typeface.NORMAL),
                fpx(refSize), colors.name);
        name.setSingleLine(false);
        name.setMaxLines(2);
        name.setLineSpacing(0f, 1.08f);
        name.setGravity(centered ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        return name;
    }

    private TextView metaView(boolean centered) {
        TextView meta = text(content.meta, Typeface.create("sans-serif", Typeface.NORMAL), fpx(26),
                colors.meta);
        meta.setLetterSpacing(0.1f);
        meta.setGravity(centered ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        return meta;
    }

    private TextView wasView(float refSize) {
        TextView was = text(deal.was, Typeface.DEFAULT_BOLD, fpx(refSize), colors.meta);
        was.setPaintFlags(was.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        return was;
    }

    private TextView priceView(float refSize, float available) {
        TextView price = new TextView(context);
        price.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        price.setLetterSpacing(-0.02f);
        price.setIncludeFontPadding(false);
        price.setSingleLine(true);
        price.setTextColor(colors.price);
        fit(price, content.price, fpx(refSize), available);
        return price;
    }

    private TextView chip(String value, int fill, int outline, int textColor, boolean bold) {
        TextView chip = text(value, bold ? Typeface.DEFAULT_BOLD
                : Typeface.create("sans-serif-medium", Typeface.NORMAL), fpx(26), textColor);
        GradientDrawable shape = rounded(fill, fpx(999));
        if (outline != 0) {
            shape.setStroke(Math.max(1, px(3)), outline);
        }
        chip.setBackground(shape);
        chip.setPadding(px(22), px(10), px(22), px(10));
        return chip;
    }

    private TextView plain(String value, Typeface typeface, float refSize, int color) {
        return text(value, typeface, fpx(refSize), color);
    }

    private static void addIfText(List<TextView> chips, TextView chip) {
        if (chip.getText().length() > 0) {
            chips.add(chip);
        }
    }

    /** A one-line label at an exact pixel size. */
    private TextView text(String value, Typeface typeface, float sizePx, int color) {
        TextView view = new TextView(context);
        view.setTypeface(typeface);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setText(value);
        return view;
    }

    /** A one-line label shrunk from {@code maxSizePx} until it fits {@code available}. */
    private TextView fitted(String value, Typeface typeface, float maxSizePx, float available,
                            int color) {
        TextView view = text(value, typeface, maxSizePx, color);
        fit(view, value, maxSizePx, available);
        return view;
    }

    /** Sets the text and the largest size up to {@code maxSizePx} that fits; returns it. */
    private static float fit(TextView view, String value, float maxSizePx, float available) {
        TextPaint paint = new TextPaint(view.getPaint());
        paint.setTextSize(maxSizePx);
        float measured = paint.measureText(value);
        // A little short of the full width: a text that fills it exactly can be
        // rounded a pixel over and ellipsized.
        available *= 0.97f;
        float size = measured > available && measured > 0f && available > 0f
                ? maxSizePx * available / measured : maxSizePx;
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        view.setText(value);
        return size;
    }

    /** The widest child of a column of one-line labels. */
    private static float measure(LinearLayout column) {
        float widest = 0f;
        for (int i = 0; i < column.getChildCount(); i++) {
            View child = column.getChildAt(i);
            if (child instanceof TextView) {
                TextView label = (TextView) child;
                widest = Math.max(widest, label.getPaint().measureText(label.getText().toString())
                        + label.getPaddingLeft() + label.getPaddingRight());
            }
        }
        return widest;
    }

    // --- Views and shapes ---------------------------------------------------

    private LinearLayout column(int gravity) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(gravity);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        return column;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        return shape;
    }

    private static GradientDrawable oval(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        return shape;
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout.LayoutParams wide(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private static LinearLayout.LayoutParams wrap(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private static LinearLayout.LayoutParams wrapGravity(int topMargin, int gravity) {
        LinearLayout.LayoutParams params = wrap(topMargin);
        params.gravity = gravity;
        return params;
    }

    private static LinearLayout.LayoutParams wrapRow() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** A size on the 1280x800 reference screen, in this panel's pixels. */
    private int px(float reference) {
        return Math.round(reference * scale);
    }

    private float fpx(float reference) {
        return reference * scale;
    }
}
