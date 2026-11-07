package com.pilipala.marcomovie;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Video detail page, fixed poster artwork column, line switcher, reversible episode ordering, and
 * frame-batched episode grid rendering extracted from {@link MainActivity}.
 */
final class DetailScreen {
    private DetailScreen() {}

    static void show(MainActivity a, Cms.Site s, Cms.Video listing, Cms.Video ready) {
        if (a.redirectListing(s, listing)) return;
        a.renderPage("", "", false);
        boolean portraitLayout = a.isPortrait() && !a.isTvDevice();
        if (portraitLayout) {
            a.body.setPadding(a.dp(18), a.dp(14), a.dp(18), a.dp(32));
        } else {
            a.body.setPadding(a.dp(34), a.dp(14), a.dp(40), a.dp(30));
        }
        TextView backButton = a.navButton("‹ 返回", "detail-back", a::onBackPressed);
        a.body.addView(backButton, new LinearLayout.LayoutParams(a.dp(82), a.dp(32)));
        a.gap(a.body, 8);
        LinearLayout hero = portraitLayout ? a.vertical() : a.row();
        hero.setTag("detail-hero");
        hero.setGravity(Gravity.TOP);
        if (portraitLayout) {
            a.body.addView(hero, new LinearLayout.LayoutParams(-1, -2));
        } else {
            a.body.addView(
                    hero,
                    new LinearLayout.LayoutParams(
                            -1,
                            a.dp(
                                    a.windowHeight() / a.getResources().getDisplayMetrics().density
                                            - 84)));
        }
        ImmersivePoster cover = new ImmersivePoster(a, "detail-cover");
        a.root.addView(cover.backdrop, 1, new FrameLayout.LayoutParams(-1, -1));
        float screenHeight = a.windowHeight() / a.getResources().getDisplayMetrics().density;
        int coverWidth =
                portraitLayout
                        ? 148
                        : Math.round(Math.min(300, Math.max(224, (screenHeight - 132) / 1.5f)));
        LinearLayout posterColumn = a.vertical();
        posterColumn.setTag("detail-poster-column");
        if (portraitLayout) {
            LinearLayout.LayoutParams columnSize = new LinearLayout.LayoutParams(-1, -2);
            columnSize.bottomMargin = a.dp(14);
            hero.addView(posterColumn, columnSize);
        } else {
            LinearLayout.LayoutParams columnSize =
                    new LinearLayout.LayoutParams(a.dp(coverWidth), -1);
            columnSize.rightMargin = a.dp(30);
            hero.addView(posterColumn, columnSize);
        }
        TextView title = a.text(listing.title, portraitLayout ? 22 : 26, MainActivity.WHITE);
        title.setTag("detail-title");
        title.setTypeface(null, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        posterColumn.addView(title);
        a.gap(posterColumn, 10);
        if (portraitLayout) {
            posterColumn.addView(cover, new LinearLayout.LayoutParams(a.dp(148), a.dp(220)));
        } else {
            posterColumn.addView(cover, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        cover.load(listing.poster);
        LinearLayout info = a.vertical();
        info.setClipChildren(false);
        if (portraitLayout) {
            hero.addView(info, new LinearLayout.LayoutParams(-1, -2));
        } else {
            ScrollView detailsScroll = new TvScrollView(a);
            detailsScroll.setTag("detail-scroll");
            detailsScroll.setClipToPadding(false);
            detailsScroll.setVerticalScrollBarEnabled(false);
            detailsScroll.addView(info, new ScrollView.LayoutParams(-1, -2));
            hero.addView(detailsScroll, new LinearLayout.LayoutParams(0, -1, 1));
            a.scroll = detailsScroll;
        }
        info.setPadding(0, a.dp(4), 0, 0);
        TextView state = a.text("正在读取详情与剧集…", 14, MainActivity.MUTED);
        state.setTag("detail-loading");
        info.addView(state);
        MainActivity.Done<Cms.Video> present =
                v -> {
                    if (v.poster == null || v.poster.isEmpty()) v.poster = listing.poster;
                    if (v.title == null || v.title.isEmpty()) v.title = listing.title;
                    title.setText(v.title);
                    if (!Objects.equals(v.poster, listing.poster)) cover.load(v.poster);
                    info.removeView(state);
                    String metadata = (v.year + "   " + v.area + "   " + v.remarks).trim();
                    if (!metadata.isEmpty()) {
                        TextView meta = a.text(metadata, 14, MainActivity.MUTED);
                        meta.setMaxLines(2);
                        info.addView(meta);
                        a.gap(info, 12);
                    }
                    if (!v.description.isEmpty()) {
                        TextView synopsis = a.text(v.description, 15, 0xffceced3);
                        synopsis.setLineSpacing(a.dp(4), 1);
                        synopsis.setMaxLines(3);
                        synopsis.setEllipsize(TextUtils.TruncateAt.END);
                        info.addView(synopsis);
                        a.addButton(
                                info,
                                a.navButton(
                                        "展开简介",
                                        "synopsis",
                                        () ->
                                                new AppDialog.Builder(a)
                                                        .setTitle(v.title)
                                                        .setMessage(v.description)
                                                        .setPositiveButton("关闭", null)
                                                        .show()));
                    }
                    if (!v.actor.isEmpty()) {
                        TextView actor = a.text("演员  " + v.actor, 13, MainActivity.MUTED);
                        actor.setMaxLines(2);
                        actor.setEllipsize(TextUtils.TruncateAt.END);
                        info.addView(actor);
                        a.gap(info, 8);
                    }
                    Button favorite =
                            a.button(
                                    Favorites.contains(
                                                    a.prefs.getString(Prefs.FAVORITES, "[]"),
                                                    s.key,
                                                    v.id)
                                            ? "已收藏"
                                            : "加入收藏",
                                    "detail-favorite",
                                    () -> {});
                    favorite.setOnClickListener(
                            view -> {
                                a.prefs
                                        .edit()
                                        .putString(
                                                Prefs.FAVORITES,
                                                Favorites.toggle(
                                                        a.prefs.getString(Prefs.FAVORITES, "[]"),
                                                        s.key,
                                                        v))
                                        .apply();
                                favorite.setText(
                                        Favorites.contains(
                                                        a.prefs.getString(Prefs.FAVORITES, "[]"),
                                                        s.key,
                                                        v.id)
                                                ? "已收藏"
                                                : "加入收藏");
                            });
                    a.addButton(info, favorite);
                    for (java.util.Iterator<Cms.Line> it = v.lines.iterator(); it.hasNext(); ) {
                        if (it.next().episodes.isEmpty()) it.remove();
                    }
                    if (v.lines.isEmpty()) {
                        info.addView(a.text("这部影片没有返回可识别的播放线路。", 15, MainActivity.MUTED));
                        favorite.requestFocus();
                        return;
                    }
                    WatchHistory.Entry bookmark = null;
                    for (WatchHistory.Entry entry : a.watchHistory())
                        if (entry.site.equals(s.key) && entry.id.equals(v.id)) {
                            bookmark = entry;
                            break;
                        }
                    final WatchHistory.Entry remembered = bookmark;
                    Button play =
                            a.button(
                                    remembered == null ? "开始播放" : "继续播放",
                                    "detail-play",
                                    () -> {
                                        if (remembered != null) a.playBookmark(s, v, remembered);
                                        else
                                            a.resolvePlayer(
                                                    s,
                                                    v,
                                                    v.lines.get(SourcePreferences.lineIndex(a, v)),
                                                    v.lines
                                                            .get(SourcePreferences.lineIndex(a, v))
                                                            .episodes
                                                            .get(0));
                                    });
                    LinearLayout actions = a.row();
                    info.removeView(favorite);
                    a.addButton(actions, play);
                    a.addButton(actions, favorite);
                    info.addView(actions);
                    if (remembered != null) {
                        TextView last =
                                a.text(
                                        "上次观看 · " + a.historyLabel(remembered),
                                        13,
                                        MainActivity.MUTED);
                        last.setTag("detail-history");
                        last.setPadding(0, a.dp(12), 0, 0);
                        info.addView(last);
                    }
                    int rememberedLine = SourcePreferences.lineIndex(a, v), rememberedPage = 0;
                    if (remembered != null)
                        for (int i = 0; i < v.lines.size(); i++)
                            if (v.lines.get(i).name.equals(remembered.line)) {
                                rememberedLine = i;
                                for (int n = 0; n < v.lines.get(i).episodes.size(); n++)
                                    if (v.lines
                                            .get(i)
                                            .episodes
                                            .get(n)
                                            .name
                                            .equals(remembered.episode))
                                        rememberedPage =
                                                (a.prefs.getBoolean(
                                                                        Prefs.EPISODES_DESCENDING,
                                                                        false)
                                                                ? v.lines.get(i).episodes.size()
                                                                        - 1
                                                                        - n
                                                                : n)
                                                        / 50;
                                break;
                            }
                    if (a.current != null && a.current.selection.selected)
                        for (int i = 0; i < v.lines.size(); i++)
                            if (v.lines.get(i).name.equals(a.current.selection.line)) {
                                rememberedLine = i;
                                rememberedPage =
                                        EpisodeSelection.boundPage(
                                                a.current.selection.page,
                                                v.lines.get(i).episodes.size());
                                break;
                            }
                    LinearLayout seasonPanel = a.vertical();
                    seasonPanel.setTag("detail-seasons");
                    info.addView(seasonPanel);
                    populateTvRelatedSeasons(a, s, v, seasonPanel);
                    LinearLayout episodePanel = a.vertical();
                    episodePanel.setTag("detail-episodes");
                    a.gap(info, 12);
                    info.addView(episodePanel);
                    renderEpisodes(a, s, v, rememberedLine, rememberedPage, episodePanel, null);
                    View saved =
                            a.current != null && a.current.focus != null
                                    ? a.root.findViewWithTag(a.current.focus)
                                    : null;
                    if (saved != null) saved.requestFocus();
                    else play.requestFocus();
                };
        if (ready == null) a.loadDetail(s, listing.id, present);
        else
            try {
                present.accept(ready);
            } catch (Exception error) {
                AppLog.w("DetailScreen", "present ready detail failed", error);
                info.addView(a.text("详情数据无法显示，请返回后重试。", 15, MainActivity.MUTED));
            }
    }

    static void renderEpisodes(
            MainActivity a,
            Cms.Site site,
            Cms.Video video,
            int lineIndex,
            int page,
            LinearLayout panel,
            String focusTag) {
        if (a.current != null)
            a.current.selection.select(
                    video.lines.get(lineIndex).name,
                    page,
                    video.lines.get(lineIndex).episodes.size());
        panel.removeAllViews();
        Cms.Line line = video.lines.get(lineIndex);
        panel.addView(
                a.navButton(
                        "默认播放线路", "line-preferred", () -> SourcePreferences.chooseLine(a, video)));
        final boolean descending = a.prefs.getBoolean(Prefs.EPISODES_DESCENDING, false);
        CategoryFlow sources = new CategoryFlow(a, () -> {});
        sources.setTag("detail-line-buttons");
        sources.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(8));
        panel.addView(sources, new LinearLayout.LayoutParams(-1, -2));
        for (int n = 0; n < video.lines.size(); n++) {
            final int index = n;
            boolean selected = n == lineIndex;
            Button choice =
                    a.button(
                            video.lines.get(n).name,
                            "detail-line:" + n,
                            () -> {
                                renderEpisodes(a, site, video, index, 0, panel, null);
                                View play = a.root.findViewWithTag("detail-play");
                                if (play instanceof Button) {
                                    ((Button) play).setText("开始播放");
                                    play.setOnClickListener(
                                            v -> {
                                                a.resumeAt = 0;
                                                a.resolvePlayer(
                                                        site,
                                                        video,
                                                        video.lines.get(index),
                                                        video.lines.get(index).episodes.get(0));
                                            });
                                }
                                View next = panel.findViewWithTag("detail-line:" + index);
                                if (next != null) next.requestFocus();
                            });
            choice.setSelected(selected);
            choice.setTextSize(13);
            choice.setMinHeight(a.dp(36));
            choice.setMinimumHeight(a.dp(36));
            choice.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8));
            choice.setBackground(a.bg(selected ? 0xff465c76 : 0xff242429, 18));
            choice.setOnFocusChangeListener(
                    (view, focus) ->
                            FocusMotion.style(
                                    a,
                                    choice,
                                    focus,
                                    selected ? 0xff465c76 : 0xff242429,
                                    MainActivity.WHITE,
                                    18,
                                    MainActivity.WHITE,
                                    MainActivity.DARK,
                                    false,
                                    1.025f));
            sources.addView(choice);
        }
        LinearLayout orderRow = a.row();
        TextView count = a.text(line.episodes.size() + " 集", 13, MainActivity.MUTED);
        count.setPadding(a.dp(4), 0, 0, a.dp(6));
        orderRow.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
        Button order =
                a.navButton(
                        descending ? "↓ 倒序" : "↑ 正序",
                        "episodes-order",
                        () -> {
                            boolean next = !descending;
                            a.prefs.edit().putBoolean(Prefs.EPISODES_DESCENDING, next).apply();
                            renderEpisodes(a, site, video, lineIndex, 0, panel, "episodes-order");
                        });
        order.setSelected(descending);
        order.setContentDescription("选集排序：" + (descending ? "倒序，点击改为正序" : "正序，点击改为倒序"));
        orderRow.addView(order);
        panel.addView(orderRow);
        TextView fullTitle = a.text("聚焦剧集可查看完整名称", 14, MainActivity.WHITE);
        fullTitle.setTag("episode-full-title");
        fullTitle.setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(10));
        fullTitle.setLineSpacing(a.dp(3), 1);
        panel.addView(fullTitle, new LinearLayout.LayoutParams(-1, -2));
        int start = page * 50, end = Math.min(start + 50, line.episodes.size());
        Object batchToken = new Object();
        panel.setTag(batchToken);
        LinearLayout cells = a.vertical();
        panel.addView(cells);
        appendEpisodeCells(
                a,
                site,
                video,
                line,
                start,
                end,
                cells,
                panel,
                batchToken,
                a.generation,
                descending,
                focusTag);
        if ("episodes-order".equals(focusTag)) order.requestFocus();
        if (line.episodes.size() > 50) {
            LinearLayout pages = a.row();
            if (page > 0)
                a.addButton(
                        pages,
                        a.navButton(
                                "上一组",
                                "episodes-previous",
                                () ->
                                        renderEpisodes(
                                                a, site, video, lineIndex, page - 1, panel, null)));
            if (end < line.episodes.size())
                a.addButton(
                        pages,
                        a.navButton(
                                "下一组",
                                "episodes-next",
                                () -> {
                                    renderEpisodes(
                                            a, site, video, lineIndex, page + 1, panel, null);
                                    View first =
                                            panel.findViewWithTag(
                                                    "episode:"
                                                            + line.name
                                                            + ":"
                                                            + (descending
                                                                    ? line.episodes.size() - 1 - end
                                                                    : end));
                                    if (first != null) first.requestFocus();
                                }));
            a.addButton(
                    pages,
                    a.navButton(
                            "全部选集",
                            "episodes-all",
                            () -> {
                                String[] labels = new String[line.episodes.size()];
                                for (int n = 0; n < labels.length; n++)
                                    labels[n] =
                                            line.episodes.get(
                                                            descending
                                                                    ? line.episodes.size() - 1 - n
                                                                    : n)
                                                    .name;
                                new AppDialog.Builder(a)
                                        .setTitle(line.name)
                                        .setItems(
                                                labels,
                                                (dialog, n) ->
                                                        a.resolvePlayer(
                                                                site,
                                                                video,
                                                                line,
                                                                line.episodes.get(
                                                                        descending
                                                                                ? line.episodes
                                                                                                .size()
                                                                                        - 1
                                                                                        - n
                                                                                : n)))
                                        .setNegativeButton("取消", null)
                                        .show();
                            }));
            panel.addView(pages);
        }
    }

    static void appendEpisodeCells(
            MainActivity a,
            Cms.Site site,
            Cms.Video video,
            Cms.Line line,
            int start,
            int end,
            LinearLayout cells,
            LinearLayout panel,
            Object token,
            int ticket,
            boolean descending,
            String focusTag) {
        if (ticket != a.generation || panel.getTag() != token) return;
        int stop = Math.min(end, start + 12);
        LinearLayout row = null;
        for (int i = start; i < stop; i++) {
            if ((i - start) % 3 == 0) {
                row = a.row();
                row.setClipChildren(false);
                row.setClipToPadding(false);
                cells.addView(row);
            }
            int originalIndex = descending ? line.episodes.size() - 1 - i : i;
            Cms.Episode episode = line.episodes.get(originalIndex);
            Button button =
                    a.button(
                            episode.name,
                            "episode:" + line.name + ":" + originalIndex,
                            () -> {
                                a.resumeAt = 0;
                                a.resolvePlayer(site, video, line, episode);
                            });
            button.setContentDescription(episode.name);
            button.setOnFocusChangeListener(
                    (view, focus) -> {
                        FocusMotion.style(
                                a,
                                button,
                                focus,
                                0xff242429,
                                MainActivity.WHITE,
                                12,
                                MainActivity.WHITE,
                                MainActivity.DARK,
                                false,
                                1.025f);
                        if (focus) {
                            if (a.current != null) {
                                a.current.episodeFocus = originalIndex;
                                a.current.episodeFocusLine = line.name;
                            }
                            TextView label = (TextView) panel.findViewWithTag("episode-full-title");
                            if (label != null) label.setText(episode.name);
                        }
                    });
            button.setSingleLine(true);
            button.setEllipsize(TextUtils.TruncateAt.END);
            button.setPadding(a.dp(8), a.dp(9), a.dp(8), a.dp(9));
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, a.dp(52), 1);
            cell.setMargins(a.dp(3), a.dp(4), a.dp(5), a.dp(6));
            row.addView(button, cell);
            if (focusTag != null && focusTag.equals(button.getTag())) button.requestFocus();
        }
        if (row != null)
            for (int n = (stop - start) % 3; n > 0 && n < 3; n++)
                row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        if (stop < end)
            cells.postOnAnimation(
                    () ->
                            appendEpisodeCells(
                                    a,
                                    site,
                                    video,
                                    line,
                                    stop,
                                    end,
                                    cells,
                                    panel,
                                    token,
                                    ticket,
                                    descending,
                                    focusTag));
        else if (a.current != null
                && a.current.focus != null
                && a.current.focus.startsWith("episode:")) {
            View focus = a.getCurrentFocus();
            Object tag = focus == null ? null : focus.getTag();
            if (focus == null || "detail-play".equals(tag) || "detail-back".equals(tag)) {
                View saved = panel.findViewWithTag(a.current.focus);
                if (saved != null) saved.requestFocus();
            }
        }
    }

    static void populateTvRelatedSeasons(
            final MainActivity a,
            final Cms.Site site,
            final Cms.Video currentVideo,
            final LinearLayout seasonPanel) {
        final MobileDetailScreen.SeasonMeta curMeta =
                MobileDetailScreen.parseSeasonMeta(currentVideo.title);
        final String baseKey =
                curMeta.baseTitle == null
                        ? ""
                        : curMeta.baseTitle
                                .toLowerCase()
                                .replaceAll("[\\s·:：\\-—_（）()【】\\[\\]《》〈〉\"“”'’!！?？,，.。]", "")
                                .trim();
        if (baseKey.isEmpty()) return;
        final int ticket = a.generation;
        a.submitPage(
                () -> {
                    try {
                        List<Cms.Video> found =
                                Cms.listing(
                                                SourceController.engine(a, site)
                                                        .search(curMeta.baseTitle, 1))
                                        .videos;
                        if (found == null || found.isEmpty()) return;
                        final Map<Integer, Cms.Video> bySeason = new TreeMap<>();
                        final Map<Integer, String> labels = new TreeMap<>();
                        bySeason.put(curMeta.seasonNumber, currentVideo);
                        labels.put(curMeta.seasonNumber, curMeta.seasonLabel);
                        for (Cms.Video cand : found) {
                            if (cand == null || cand.title == null) continue;
                            MobileDetailScreen.SeasonMeta candMeta =
                                    MobileDetailScreen.parseSeasonMeta(cand.title);
                            String candKey =
                                    candMeta.baseTitle
                                            .toLowerCase()
                                            .replaceAll(
                                                    "[\\s·:：\\-—_（）()【】\\[\\]《》〈〉\"“”'’!！?？,，.。]",
                                                    "")
                                            .trim();
                            if (!baseKey.equals(candKey)) continue;
                            if (!curMeta.explicit
                                    && !candMeta.explicit
                                    && !Objects.equals(cand.id, currentVideo.id)) {
                                continue;
                            }
                            if (!bySeason.containsKey(candMeta.seasonNumber)) {
                                bySeason.put(candMeta.seasonNumber, cand);
                                labels.put(candMeta.seasonNumber, candMeta.seasonLabel);
                            }
                        }
                        if (bySeason.size() < 2) return;
                        final List<Integer> seasonNums = new ArrayList<>(bySeason.keySet());
                        a.ui.post(
                                () -> {
                                    if (a.isFinishing()
                                            || a.isDestroyed()
                                            || ticket != a.generation) return;
                                    seasonPanel.removeAllViews();
                                    a.gap(seasonPanel, 10);
                                    CategoryFlow flow = new CategoryFlow(a, () -> {});
                                    flow.setTag("detail-season-buttons");
                                    flow.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
                                    seasonPanel.addView(
                                            flow, new LinearLayout.LayoutParams(-1, -2));
                                    for (final Integer sNum : seasonNums) {
                                        final Cms.Video targetVid = bySeason.get(sNum);
                                        String sLabel = labels.get(sNum);
                                        boolean active =
                                                Objects.equals(targetVid.id, currentVideo.id)
                                                        || sNum == curMeta.seasonNumber;
                                        Button btn =
                                                a.button(
                                                        sLabel,
                                                        "detail-season:" + sNum,
                                                        () -> {
                                                            if (!Objects.equals(
                                                                    targetVid.id,
                                                                    currentVideo.id)) {
                                                                show(a, site, targetVid, null);
                                                            }
                                                        });
                                        if (active) {
                                            btn.setBackground(a.bg(0xff465c76, 10));
                                            btn.setOnFocusChangeListener(
                                                    (v, f) ->
                                                            FocusMotion.style(
                                                                    a,
                                                                    btn,
                                                                    f,
                                                                    0xff465c76,
                                                                    MainActivity.WHITE,
                                                                    10,
                                                                    MainActivity.WHITE,
                                                                    MainActivity.DARK,
                                                                    true,
                                                                    1.02f));
                                        }
                                        flow.addView(btn);
                                    }
                                });
                    } catch (Exception ignored) {
                    }
                });
    }
}
