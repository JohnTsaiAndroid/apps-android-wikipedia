package org.wikipedia;

import android.app.Application;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.view.Window;
import android.webkit.WebView;
import com.squareup.otto.Bus;
import org.acra.ACRA;
import org.acra.ReportingInteractionMode;
import org.acra.annotation.ReportsCrashes;
import org.apache.commons.lang3.ArrayUtils;
import org.mediawiki.api.json.Api;
import org.wikipedia.analytics.FunnelManager;
import org.wikipedia.analytics.SessionFunnel;
import org.wikipedia.bridge.StyleLoader;
import org.wikipedia.data.ContentPersister;
import org.wikipedia.data.DBOpenHelper;
import org.wikipedia.editing.EditTokenStorage;
import org.wikipedia.editing.summaries.EditSummary;
import org.wikipedia.editing.summaries.EditSummaryPersister;
import org.wikipedia.events.ChangeTextSizeEvent;
import org.wikipedia.events.ThemeChangeEvent;
import org.wikipedia.history.HistoryEntry;
import org.wikipedia.history.HistoryEntryPersister;
import org.wikipedia.login.UserInfoStorage;
import org.wikipedia.migration.PerformMigrationsTask;
import org.wikipedia.networking.MccMncStateHandler;
import org.wikipedia.page.PageCache;
import org.wikipedia.pageimages.PageImage;
import org.wikipedia.pageimages.PageImagePersister;
import org.wikipedia.savedpages.SavedPage;
import org.wikipedia.savedpages.SavedPagePersister;
import org.wikipedia.search.RecentSearch;
import org.wikipedia.search.RecentSearchPersister;
import org.wikipedia.settings.PrefKeys;
import org.wikipedia.util.ApiUtil;
import org.wikipedia.zero.WikipediaZeroHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@ReportsCrashes(
        formKey = "",
        mode = ReportingInteractionMode.DIALOG,
        resDialogTitle = R.string.acra_report_dialog_title,
        resDialogText = R.string.acra_report_dialog_text,
        resDialogCommentPrompt = R.string.acra_report_dialog_comment,
        mailTo = "mobile-android-wikipedia-crashes@wikimedia.org")
public class WikipediaApp extends Application {
    private static final String FALLBACK_WIKI_LANG_CODE = "en"; // Must exist in preference_language_keys.
    private static final String HTTPS_PROTOCOL = "https";

    private float screenDensity;
    public float getScreenDensity() {
        return screenDensity;
    }

    // Reload in onCreate to override
    private String networkProtocol = HTTPS_PROTOCOL;
    public String getNetworkProtocol() {
        return networkProtocol;
    }

    private boolean sslFallback = false;
    public boolean getSslFallback() {
        return sslFallback;
    }
    public void setSslFallback(boolean fallback) {
        sslFallback = fallback;
    }

    private int sslFailCount = 0;
    public int getSslFailCount() {
        return sslFailCount;
    }
    public int incSslFailCount() {
        return ++sslFailCount;
    }

    public static final int THEME_LIGHT = R.style.Theme_WikiLight;
    public static final int THEME_DARK = R.style.Theme_WikiDark;

    public static final int FONT_SIZE_MULTIPLIER_MIN = -5;
    public static final int FONT_SIZE_MULTIPLIER_MAX = 8;
    private static final float FONT_SIZE_FACTOR = 0.1f;

    public static final int RELEASE_PROD = 0;
    public static final int RELEASE_BETA = 1;
    public static final int RELEASE_ALPHA = 2;
    private int releaseType = RELEASE_PROD;

    public static final int PREFERRED_THUMB_SIZE = 96;

    /**
     * Returns a constant that tells whether this app is a production release,
     * a beta release, or an alpha (continuous integration) release.
     * @return Release type of the app.
     */
    public int getReleaseType() {
        return releaseType;
    }

    private String[] wikiCodes;

    private List<String> languageMruList;

    private SessionFunnel sessionFunnel;
    public SessionFunnel getSessionFunnel() {
        return sessionFunnel;
    }

    /**
     * Singleton instance of WikipediaApp
     */
    private static WikipediaApp INSTANCE;

    private Bus bus;
    private int currentTheme = 0;

    private WikipediaZeroHandler zeroHandler;
    public WikipediaZeroHandler getWikipediaZeroHandler() {
        return zeroHandler;
    }

    /**
     * Our page cache, which discards the eldest entries based on access time.
     * This will allow the user to go "back" smoothly (the previous page is guaranteed
     * to be in cache), but also to go "forward" smoothly (if the user clicks on a link
     * that was already visited within a short time).
     */
    private PageCache pageCache;
    public PageCache getPageCache() {
        return pageCache;
    }

    /**
     * Preference manager for storing things like the app's install IDs for EventLogging, theme,
     * font size, etc.
     */
    private SharedPreferences prefs;

    public WikipediaApp() {
        INSTANCE = this;
    }

    /**
     * Returns the singleton instance of the WikipediaApp
     *
     * This is ok, since android treats it as a singleton anyway.
     */
    public static WikipediaApp getInstance() {
        return INSTANCE;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ACRA.init(this);

        if (BuildConfig.APPLICATION_ID.contains("beta")) {
            releaseType = RELEASE_BETA;
        } else if (BuildConfig.APPLICATION_ID.contains("alpha")) {
            releaseType = RELEASE_ALPHA;
        }

        bus = new Bus();

        final Resources resources = getResources();
        ViewAnimations.init(resources);
        screenDensity = resources.getDisplayMetrics().density;

        this.prefs = PreferenceManager.getDefaultSharedPreferences(this);
        PrefKeys.initPreferenceKeys(resources);

        sessionFunnel = new SessionFunnel(this);

        // Enable debugging on the webview
        if (ApiUtil.hasKitKat()) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        Api.setConnectionFactory(new OkHttpConnectionFactory(this));

        zeroHandler = new WikipediaZeroHandler(this);
        pageCache = new PageCache(this);

        new PerformMigrationsTask().execute();
    }

    public Bus getBus() {
        return bus;
    }


    private String userAgent;
    public String getUserAgent() {
        if (userAgent == null) {
            String channel = Utils.getChannel(this);
            channel = channel.equals("") ? channel : " ".concat(channel);
            userAgent = String.format("WikipediaApp/%s (Android %s; %s)%s",
                    BuildConfig.VERSION_NAME,
                    Build.VERSION.RELEASE,
                    getString(R.string.device_type),
                    channel
            );
        }
        return userAgent;
    }

    /**
     * @return the value that should go in the Accept-Language header.
     */
    public String getAcceptLanguage() {
        // https://lists.wikimedia.org/pipermail/mobile-l/2014-July/007514.html
        String primaryAppLanguage = getPrimaryLanguage();
        String acceptLanguage = primaryAppLanguage;
        Locale defaultLocale = Locale.getDefault();
        String wikiLanguage = Utils.langCodeToWikiLang(defaultLocale.getLanguage());

        // For now, let's only modify Accept-Language for Chinese languages.
        // Chinese language users have been reporting that the wrong language
        // is being displayed. In case the app setting is not Chinese (e.g.,
        // it's English), yet the user navigates to a page in Chinese, or a
        // page containing Chinese dialect templates, we want to show the
        // correct dialect there, too.
        if (primaryAppLanguage.equals("zh") || wikiLanguage.equals("zh")) {
            // The next two lines are inside the if() guard just for speed. In the future when we remove the if
            // guard, they will always get called.
            String country = defaultLocale.getCountry();
            String langWithCountryCode = TextUtils.isEmpty(country)
                    ? wikiLanguage
                    : wikiLanguage + "-" + country.toLowerCase(Locale.ROOT);
            if (primaryAppLanguage.equals(langWithCountryCode)) {
                // The app setting agrees with the system locale, so:
                // -If the system locale was overly simplistic (just the language, no country), use the app setting
                // -If the system locale (and app setting) was specific, use the specific value followed by the general
                // This should help to ensure that the correct dialect is displayed on pages that have templating.
                acceptLanguage = langWithCountryCode.equals(wikiLanguage)
                        ? primaryAppLanguage
                        : primaryAppLanguage + "," + wikiLanguage + ";q=0.9";
            } else if (primaryAppLanguage.equals(wikiLanguage)) {
                // The app setting does not agree with the system locale, but the base language (e.g., zh) matches, so
                // use the specific value followed by the general. This way the user will get the more specific
                // dialect displayed on pages that have templating.
                acceptLanguage = langWithCountryCode + "," + primaryAppLanguage + ";q=0.9";
            } else {
                // In the case that the app setting doesn't map up at all to the system locale, express to the server
                // that the first preference is the app setting's language, followed by the specific system locale
                // followed by the general system locale. In principle, this should result in correct templating in
                // the case that the article is in the non-system locale, yet contains templated glyphs in the
                // system locale.
                acceptLanguage = primaryAppLanguage + "," + langWithCountryCode + ";q=0.9";
                if (!langWithCountryCode.equals(wikiLanguage)) {
                    acceptLanguage = acceptLanguage + "," + wikiLanguage + ";q=0.8";
                }
            }
        }
        return acceptLanguage;
    }

    private HashMap<String, Api> apis = new HashMap<>();
    private MccMncStateHandler mccMncStateHandler = new MccMncStateHandler();
    public Api getAPIForSite(Site site) {
        // https://lists.wikimedia.org/pipermail/wikimedia-l/2014-April/071131.html
        HashMap<String, String> customHeaders = new HashMap<>();
        customHeaders.put("User-Agent", getUserAgent());
        // Add the app install ID to the header, but only if the user has not opted out of logging
        if (isEventLoggingEnabled()) {
            customHeaders.put("X-WMF-UUID", getAppInstallID());
        }
        String acceptLanguage = getAcceptLanguage();

        // TODO: once we're not constraining this to just Chinese, add the header unconditionally.
        // Note: the Accept-Language header is unconditionally added based on device locale with a
        // a single value (e.g., en-us or zh-cn) *by the platform* on the other app platform. Contrast
        // that with *this* platform, which does not do this for some reason (possibly on side effect free
        // method invocation theory. But given that up until now we haven't been adding the Accept-Language
        // header manually on this platform, we don't want to just arbitrarily start doing so for *all* requests.
        if (!acceptLanguage.equals(getPrimaryLanguage())) {
            customHeaders.put("Accept-Language", acceptLanguage);
        }

        // Because the mccMnc enrichment is a one-time thing, we don't need to have a complex hash key
        // for the apis HashMap<String, Api> like we do below. It naturally gets the correct
        // Accept-Language header from above, when applicable.
        Api api = mccMncStateHandler.makeApiWithMccMncHeaderEnrichment(this, site, customHeaders);
        if (api == null) {
            String domainAndApiAndVariantKey = site.getDomain() + "-" + site.getApiDomain() + "-" + acceptLanguage;
            if (!apis.containsKey(domainAndApiAndVariantKey)) {
                apis.put(domainAndApiAndVariantKey, new Api(site.getApiDomain(), site.getUseSecure(), site.getScriptPath("api.php"), customHeaders));
            }
            api = apis.get(domainAndApiAndVariantKey);
        }

        api.setHeaderCheckListener(zeroHandler);
        return api;
    }

    private Site primarySite;

    /**
     * Default site of the application
     * You should use PageTitle.getSite() to get the currently browsed site
     */
    public Site getPrimarySite() {
        if (primarySite == null) {
            primarySite = Site.forLang(getPrimaryLanguage());
        }

        return primarySite;
    }

    /**
     * Convenience method to get an API object for the primary site.
     *
     * @return An API object that is equivalent to calling getAPIForSite(getPrimarySite)
     */
    public Api getPrimarySiteApi() {
        return getAPIForSite(getPrimarySite());
    }

    private String primaryLanguage;
    public String getPrimaryLanguage() {
        if (primaryLanguage == null) {
            primaryLanguage = prefs.getString(PrefKeys.getContentLanguageKey(), null);
            if (primaryLanguage == null) {
                // No preference set!
                String wikiCode = Utils.langCodeToWikiLang(Locale.getDefault().getLanguage());
                if (!isWikiLanguage(wikiCode)) {
                    wikiCode = FALLBACK_WIKI_LANG_CODE; // fallback, see comments in #findWikiIndex
                }
                return wikiCode;
            }
        }
        return primaryLanguage;
    }

    public void setPrimaryLanguage(String language) {
        primaryLanguage = language;
        prefs.edit().putString(PrefKeys.getContentLanguageKey(), language).apply();
        primarySite = null;
    }


    private DBOpenHelper dbOpenHelper;
    public DBOpenHelper getDbOpenHelper() {
        if (dbOpenHelper == null) {
            dbOpenHelper = new DBOpenHelper(this);
        }
        return dbOpenHelper;
    }

    private HashMap<String, ContentPersister> persisters = new HashMap<>();
    public ContentPersister getPersister(Class cls) {
        if (!persisters.containsKey(cls.getCanonicalName())) {
            ContentPersister persister;
            if (cls.equals(HistoryEntry.class)) {
                persister = new HistoryEntryPersister(this);
            } else if (cls.equals(PageImage.class)) {
                persister = new PageImagePersister(this);
            } else if (cls.equals(RecentSearch.class)) {
                persister = new RecentSearchPersister(this);
            } else if (cls.equals(SavedPage.class)) {
                persister = new SavedPagePersister(this);
            } else if (cls.equals(EditSummary.class)) {
                persister = new EditSummaryPersister(this);
            } else {
                throw new RuntimeException("No persister found for class " + cls.getCanonicalName());
            }
            persisters.put(cls.getCanonicalName(), persister);
        }
        return persisters.get(cls.getCanonicalName());
    }

    public int findWikiIndex(String wikiCode) {
        int index = ArrayUtils.indexOf(getWikiCodes(), wikiCode);
        if (index == ArrayUtils.INDEX_NOT_FOUND) {
            // FIXME: Instrument this with EL to find out what is happening on places where there is a lang we can't find
            // In the meantime, just fall back to en. See https://bugzilla.wikimedia.org/show_bug.cgi?id=66140
            return findWikiIndex(FALLBACK_WIKI_LANG_CODE);
        }
        return index;
    }

    private boolean isWikiLanguage(String lang) {
        return ArrayUtils.contains(getWikiCodes(), lang);
    }

    private String[] getWikiCodes() {
        if (wikiCodes == null) {
            wikiCodes = getResources().getStringArray(R.array.preference_language_keys);
        }
        return wikiCodes;
    }

    private RemoteConfig remoteConfig;
    public RemoteConfig getRemoteConfig() {
        if (remoteConfig == null) {
            remoteConfig = new RemoteConfig(prefs);
        }
        return remoteConfig;
    }

    private String[] canonicalNames;
    public String canonicalNameFor(int index) {
        if (canonicalNames == null) {
            canonicalNames = getResources().getStringArray(R.array.preference_language_canonical_names);
        }
        return canonicalNames[index];
    }

    private String[] localNames;
    public String localNameFor(int index) {
        if (localNames == null) {
            localNames = getResources().getStringArray(R.array.preference_language_local_names);
        }
        return localNames[index];
    }

    private EditTokenStorage editTokenStorage;
    public EditTokenStorage getEditTokenStorage() {
        if (editTokenStorage == null) {
            editTokenStorage = new EditTokenStorage(this);
        }
        return editTokenStorage;
    }

    private SharedPreferenceCookieManager cookieManager;
    public SharedPreferenceCookieManager getCookieManager() {
        if (cookieManager == null) {
            cookieManager = new SharedPreferenceCookieManager(prefs);
        }
        return cookieManager;
    }

    private UserInfoStorage userInfoStorage;
    public UserInfoStorage getUserInfoStorage() {
        if (userInfoStorage == null) {
            userInfoStorage = new UserInfoStorage(prefs);
        }
        return userInfoStorage;
    }

    private FunnelManager funnelManager;
    public FunnelManager getFunnelManager() {
        if (funnelManager == null) {
            funnelManager = new FunnelManager(this);
        }

        return funnelManager;
    }

    private StyleLoader styleLoader;
    public StyleLoader getStyleLoader() {
        if (styleLoader == null) {
            styleLoader = new StyleLoader(this);
        }
        return styleLoader;
    }

    /**
     * Get this app's unique install ID, which is a UUID that should be unique for each install
     * of the app. Useful for anonymous analytics.
     * @return Unique install ID for this app.
     */
    public String getAppInstallID() {
        return getAppInstallIDForFeature(PrefKeys.getAppInstallId());
    }

    /**
     * Get an integer-valued install ID for this app (based on the last four hex digits of the
     * actual install ID of the app). Note that this value will *not* be unique for every install
     * of the app. Instead, this value should be used for feature-flagging and A/B testing of
     * new features.
     * @return Integer-valued install ID for this app, which can range from 0 to 65535.
     */
    public int getAppInstallIDInt() {
        final int hexBase = 16;
        final int uuidSubstrLen = 4;
        return Integer.parseInt(getAppInstallID().substring(getAppInstallID().length() - uuidSubstrLen), hexBase);
    }

    /**
     * Returns the unique app install ID for a feature. The app install ID is used to track unique
     * users of each feature for the purpose of improving the app's user experience.
     * @param  prefKey a key from PrefKeys for a feature with a unique app install ID
     * @return         the unique app install ID of the specified feature
     */
    private String getAppInstallIDForFeature(String prefKey) {
        String installID;
        if (prefs.contains(prefKey)) {
            installID = prefs.getString(prefKey, null);
        } else {
            installID = UUID.randomUUID().toString();
            prefs.edit().putString(prefKey, installID).apply();
        }
        return installID;
    }

    /**
     * Gets the currently-selected theme for the app.
     * @return Theme that is currently selected, which is the actual theme ID that can
     * be passed to setTheme() when creating an activity.
     */
    public int getCurrentTheme() {
        if (currentTheme == 0) {
            currentTheme = prefs.getInt(PrefKeys.getColorTheme(), THEME_LIGHT);
            if (currentTheme != THEME_LIGHT && currentTheme != THEME_DARK) {
                currentTheme = THEME_LIGHT;
            }
        }
        return currentTheme;
    }

    /**
     * Sets the theme of the app. If the new theme is the same as the current theme, nothing happens.
     * Otherwise, an event is sent to notify of the theme change.
     * @param newTheme
     */
    public void setCurrentTheme(int newTheme) {
        if (newTheme == currentTheme) {
            return;
        }
        currentTheme = newTheme;
        prefs.edit().putInt(PrefKeys.getColorTheme(), currentTheme).apply();
        bus.post(new ThemeChangeEvent());
    }

    /**
     * Apply a tint to the provided drawable.
     * @param d Drawable to be tinted.
     * @param tintColor Color of the tint. Setting to 0 will remove the tint.
     */
    public void setDrawableTint(Drawable d, int tintColor) {
        if (tintColor == 0) {
            d.clearColorFilter();
        } else {
            d.setColorFilter(tintColor, PorterDuff.Mode.SRC_ATOP);
        }
    }

    /**
     * Make adjustments to a Drawable object to look better in the current theme.
     * (e.g. apply a white color filter for night mode)
     * @param d Drawable to be adjusted.
     */
    public void adjustDrawableToTheme(Drawable d) {
        setDrawableTint(d, currentTheme == THEME_DARK ? Color.WHITE : 0);
    }

    /**
     * Make adjustments to a link or button Drawable object to look better in the current theme.
     * (e.g. apply a blue color filter for night mode, )
     * @param d Drawable to be adjusted.
     */
    public void adjustLinkDrawableToTheme(Drawable d) {
        setDrawableTint(d, getResources().getColor(currentTheme == THEME_DARK ? R.color.button_dark : R.color.button_light));
    }

    public int getFontSizeMultiplier() {
        return prefs.getInt(PrefKeys.getTextSizeMultiplier(), 0);
    }

    public void setFontSizeMultiplier(int multiplier) {
        if (multiplier < FONT_SIZE_MULTIPLIER_MIN) {
            multiplier = FONT_SIZE_MULTIPLIER_MIN;
        } else if (multiplier > FONT_SIZE_MULTIPLIER_MAX) {
            multiplier = FONT_SIZE_MULTIPLIER_MAX;
        }
        prefs.edit().putInt(PrefKeys.getTextSizeMultiplier(), multiplier).apply();
        bus.post(new ChangeTextSizeEvent());
    }

    /**
     * Gets the current size of the app's font. This is given as a device-specific size (not "sp"),
     * and can be passed directly to setTextSize() functions.
     * @param window The window on which the font will be displayed.
     * @return Actual current size of the font.
     */
    public float getFontSize(Window window) {
        int multiplier = prefs.getInt(PrefKeys.getTextSizeMultiplier(), 0);
        return Utils.getFontSizeFromSp(window, getResources().getDimension(R.dimen.textSize)) * (1.0f + multiplier * FONT_SIZE_FACTOR);
    }

    /**
     * Gets whether EventLogging is currently enabled or disabled.
     *
     * @return A boolean that is true if EventLogging is enabled, and false if it is not.
     */
    public boolean isEventLoggingEnabled() {
        return prefs.getBoolean(PrefKeys.getEventLoggingEnabled(), true);
    }

    public List<String> getLanguageMruList() {
        lazyInitLanguageToMruList();
        return languageMruList;
    }

    public void addLanguageToMruList(String langCode) {
        lazyInitLanguageToMruList();
        languageMruList.remove(langCode);
        languageMruList.add(0, langCode);
        prefs.edit().putString(PrefKeys.getLanguageMru(), TextUtils.join(",", languageMruList)).apply();
    }

    public boolean showImages() {
        return prefs.getBoolean(PrefKeys.getShowImages(), true);
    }

    private void lazyInitLanguageToMruList() {
        if (languageMruList == null) {
            languageMruList = new ArrayList<>();
            String mruString = prefs.getString(PrefKeys.getLanguageMru(), getPrimaryLanguage());
            languageMruList.addAll(csvToList(mruString));
        }
    }

    private List<String> csvToList(String commaDelimitedString) {
        return Arrays.asList(commaDelimitedString.split(","));
    }
}
