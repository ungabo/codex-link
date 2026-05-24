package com.codexlink.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Log;
import android.util.Patterns;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String TAG = "CodexLink";
    private static final String PREFS = "codex_link";
    private static final String PREF_ENDPOINT = "endpoint";
    private static final String PREF_TOKEN = "token";
    private static final String PREF_HISTORY = "history";
    private static final String PREF_CONNECTION_MODE = "connection_mode";
    private static final String PREF_LOCAL_ENDPOINT = "local_endpoint";
    private static final String PREF_LOCAL_TOKEN = "local_token";
    private static final String PREF_WEB_ENDPOINT = "web_endpoint";
    private static final String PREF_WEB_TOKEN = "web_token";
    private static final String MODE_LOCAL = "local";
    private static final String MODE_WEB = "web";
    private static final String DEFAULT_WEB_ENDPOINT = "https://www.sitesindevelopment.com/codex-link/index.php/link";
    private static final String DEFAULT_WEB_TOKEN = BuildConfig.CODEX_LINK_DEFAULT_WEB_TOKEN;

    private static final int COLOR_BACKGROUND = Color.rgb(246, 244, 239);
    private static final int COLOR_INK = Color.rgb(35, 39, 38);
    private static final int COLOR_MUTED = Color.rgb(89, 95, 93);
    private static final int COLOR_PANEL = Color.WHITE;
    private static final int COLOR_BORDER = Color.rgb(219, 215, 206);
    private static final int COLOR_PRIMARY = Color.rgb(28, 107, 90);
    private static final int COLOR_ACCENT = Color.rgb(217, 88, 69);
    private static final int COLOR_GOLD = Color.rgb(228, 189, 88);
    private static final int THREAD_PAGE_SIZE = 40;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int REQUEST_PICK_IMAGE = 4101;
    private static final int MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024;

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("MMM d, h:mm a", Locale.US);

    private Handler mainHandler;
    private SharedPreferences preferences;
    private ScrollView rootScrollView;
    private EditText endpointInput;
    private EditText tokenInput;
    private Spinner connectionModeSpinner;
    private EditText contentInput;
    private EditText threadPromptInput;
    private EditText chatFilterInput;
    private EditText threadSearchInput;
    private TextView statusView;
    private TextView catalogStatusView;
    private TextView threadTurnStatusView;
    private TextView threadResponseText;
    private TextView attachmentStatusView;
    private LinearLayout catalogSectionLayout;
    private LinearLayout sendSectionLayout;
    private LinearLayout historySectionLayout;
    private LinearLayout threadComposerLayout;
    private LinearLayout threadResponseOverlay;
    private LinearLayout attachmentPreviewLayout;
    private LinearLayout queuedTurnListLayout;
    private View sendSectionSpacer;
    private View historySectionSpacer;
    private LinearLayout chatListLayout;
    private LinearLayout threadControlsLayout;
    private LinearLayout messageListLayout;
    private TextView catalogView;
    private TextView historyView;
    private View chatFilterSpacer;
    private View chatSortSpacer;
    private Spinner chatSortSpinner;
    private Button sendButton;
    private Button threadSendButton;
    private Button loadCatalogButton;
    private Button attachImageButton;
    private String currentThreadId;
    private String currentThreadTitle;
    private String currentThreadCwd = "";
    private String currentThreadActiveTurnId = "";
    private String currentThreadSearchQuery = "";
    private String currentConnectionMode = MODE_LOCAL;
    private String queueThreadId;
    private Uri selectedImageUri;
    private String selectedImageName = "";
    private String selectedImageMimeType = "";
    private JSONArray editingQueuedImages;
    private JSONObject sendingThreadPayload;
    private int loadedRangeStart = 0;
    private int loadedRangeEnd = 0;
    private int totalThreadMessages = 0;
    private int currentThreadSearchMatch = -1;
    private int fastPollRemaining = 0;
    private boolean hasMoreThreadMessages = false;
    private boolean isLoadingThreadPage = false;
    private boolean isSendingThreadTurn = false;
    private boolean currentThreadActive = false;
    private boolean isPollingThread = false;
    private boolean hasLoadedCatalog = false;
    private boolean threadActionsExpanded = false;
    private boolean currentThreadFullLoaded = false;
    private boolean applyingConnectionMode = false;
    private int loadedCatalogChatCount = 0;
    private JSONArray loadedCatalogChats = new JSONArray();
    private JSONArray loadedThreadMessages = new JSONArray();
    private final ArrayList<QueuedTurn> queuedThreadTurns = new ArrayList<>();
    private final ArrayList<View> renderedMessageViews = new ArrayList<>();
    private final ArrayList<String> renderedMessageTexts = new ArrayList<>();
    private final ArrayList<Integer> threadSearchMatches = new ArrayList<>();
    private final Runnable threadPollRunnable = new Runnable() {
        @Override
        public void run() {
            pollCurrentThread();
        }
    };

    private static class QueuedTurn {
        final String id;
        final JSONObject payload;
        final long createdAt;

        QueuedTurn(JSONObject payload) {
            this.id = UUID.randomUUID().toString();
            this.payload = payload;
            this.createdAt = System.currentTimeMillis();
        }

        QueuedTurn(JSONObject payload, String id, long createdAt) {
            this.id = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
            this.payload = payload;
            this.createdAt = createdAt > 0 ? createdAt : System.currentTimeMillis();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);

        setContentView(buildContentView());
        restoreSavedState();
        applyIncomingIntent(getIntent(), false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIncomingIntent(intent, true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopThreadPoll();
        networkExecutor.shutdownNow();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_IMAGE || resultCode != RESULT_OK || data == null) {
            return;
        }

        Uri uri = data.getData();
        if (uri == null) {
            setThreadTurnStatus("No image selected.", true);
            return;
        }

        try {
            final int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // Some pickers grant a temporary read URI only; that is enough for immediate send.
        }

        selectedImageUri = uri;
        selectedImageName = displayNameForUri(uri);
        selectedImageMimeType = getContentResolver().getType(uri);
        if (selectedImageMimeType == null || selectedImageMimeType.isEmpty()) {
            selectedImageMimeType = "image/*";
        }
        updateAttachmentPreview();
        setThreadTurnStatus("Image attached.", false);
    }

    private View buildContentView() {
        FrameLayout shell = new FrameLayout(this);

        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(COLOR_BACKGROUND);
        shell.addView(frame, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        threadControlsLayout = new LinearLayout(this);
        threadControlsLayout.setOrientation(LinearLayout.VERTICAL);
        threadControlsLayout.setPadding(dp(10), dp(6), dp(10), dp(6));
        threadControlsLayout.setBackground(outlineDrawable(COLOR_PANEL, COLOR_BORDER, 0));
        threadControlsLayout.setElevation(dp(4));
        threadControlsLayout.setVisibility(View.GONE);
        frame.addView(threadControlsLayout, matchWrap());

        rootScrollView = new ScrollView(this);
        rootScrollView.setFillViewport(true);
        rootScrollView.setBackgroundColor(COLOR_BACKGROUND);
        rootScrollView.setVerticalScrollBarEnabled(true);
        rootScrollView.setScrollbarFadingEnabled(false);
        rootScrollView.setScrollBarSize(dp(12));
        rootScrollView.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        rootScrollView.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (scrollY == 0 && oldScrollY > 0 && hasMoreThreadMessages && !isLoadingThreadPage) {
                loadOlderThreadMessages();
            }
        });
        rootScrollView.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                hideThreadResponseOverlay();
            }
            return false;
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(24), dp(22), dp(28));
        rootScrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        frame.addView(rootScrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f));

        threadComposerLayout = buildThreadComposer();
        threadComposerLayout.setVisibility(View.GONE);

        TextView eyebrow = text("MOBILE COMPANION", 12, COLOR_PRIMARY, Typeface.BOLD);
        eyebrow.setLetterSpacing(0.08f);
        root.addView(eyebrow);

        TextView title = text("Codex Link", 32, COLOR_INK, Typeface.BOLD);
        title.setPadding(0, dp(4), 0, 0);
        root.addView(title);

        TextView subtitle = text("Send browser links, selected text, and notes from Android to a desktop Codex endpoint.", 15, COLOR_MUTED, Typeface.NORMAL);
        subtitle.setLineSpacing(dp(2), 1.0f);
        subtitle.setPadding(0, dp(4), 0, dp(18));
        root.addView(subtitle);

        root.addView(buildSettingsSection());
        root.addView(spacer(dp(14)));
        root.addView(buildCatalogSection());
        sendSectionSpacer = spacer(dp(14));
        root.addView(sendSectionSpacer);
        sendSectionLayout = buildSendSection();
        root.addView(sendSectionLayout);
        historySectionSpacer = spacer(dp(14));
        root.addView(historySectionSpacer);
        historySectionLayout = buildHistorySection();
        root.addView(historySectionLayout);

        threadResponseOverlay = buildThreadResponseOverlay();
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        overlayParams.setMargins(dp(12), 0, dp(12), dp(12));
        shell.addView(threadResponseOverlay, overlayParams);

        return shell;
    }

    private LinearLayout buildSettingsSection() {
        LinearLayout section = section();
        section.addView(sectionTitle("Connection"));

        connectionModeSpinner = new Spinner(this);
        ArrayAdapter<String> connectionAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new String[]{"Local Windows", "Web Link"});
        connectionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        connectionModeSpinner.setAdapter(connectionAdapter);
        connectionModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (applyingConnectionMode) {
                    return;
                }
                saveConnectionFieldsForMode(currentConnectionMode);
                currentConnectionMode = position == 1 ? MODE_WEB : MODE_LOCAL;
                preferences.edit().putString(PREF_CONNECTION_MODE, currentConnectionMode).apply();
                applyConnectionFieldsForMode(currentConnectionMode);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        section.addView(connectionModeSpinner, matchWrap());

        section.addView(spacer(dp(10)));

        endpointInput = input("http://192.168.1.20:8765/link", false);
        endpointInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        section.addView(endpointInput);

        section.addView(spacer(dp(10)));

        tokenInput = input("Pairing token (optional)", false);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        section.addView(tokenInput);

        section.addView(spacer(dp(12)));

        Button saveButton = button("Save", COLOR_PRIMARY, Color.WHITE);
        saveButton.setOnClickListener(view -> saveSettings());
        section.addView(saveButton, matchWrap());

        return section;
    }

    private LinearLayout buildThreadComposer() {
        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.VERTICAL);
        composer.setPadding(0, dp(6), 0, 0);
        composer.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        attachImageButton = toolbarButton("Image", Color.WHITE, COLOR_PRIMARY);
        attachImageButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
        attachImageButton.setOnClickListener(view -> pickThreadImage());
        row.addView(attachImageButton, new LinearLayout.LayoutParams(dp(74), dp(46)));

        row.addView(spacer(dp(6)));

        threadPromptInput = input("Message Codex in this chat", true);
        threadPromptInput.setMinLines(1);
        threadPromptInput.setMaxLines(4);
        threadPromptInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        row.addView(threadPromptInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(spacer(dp(6)));

        threadSendButton = button("Send", COLOR_ACCENT, Color.WHITE);
        threadSendButton.setOnClickListener(view -> sendThreadPrompt());
        row.addView(threadSendButton, new LinearLayout.LayoutParams(dp(76), dp(46)));

        composer.addView(row, matchWrap());

        attachmentPreviewLayout = new LinearLayout(this);
        attachmentPreviewLayout.setOrientation(LinearLayout.HORIZONTAL);
        attachmentPreviewLayout.setGravity(Gravity.CENTER_VERTICAL);
        attachmentPreviewLayout.setPadding(0, dp(5), 0, 0);
        attachmentPreviewLayout.setVisibility(View.GONE);

        attachmentStatusView = text("", 12, COLOR_MUTED, Typeface.NORMAL);
        attachmentStatusView.setSingleLine(true);
        attachmentPreviewLayout.addView(attachmentStatusView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button clearAttachmentButton = toolbarButton("X", Color.WHITE, COLOR_MUTED);
        clearAttachmentButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
        clearAttachmentButton.setOnClickListener(view -> clearSelectedImage());
        attachmentPreviewLayout.addView(clearAttachmentButton, new LinearLayout.LayoutParams(dp(42), dp(34)));
        composer.addView(attachmentPreviewLayout, matchWrap());

        threadTurnStatusView = text("", 12, COLOR_MUTED, Typeface.NORMAL);
        threadTurnStatusView.setSingleLine(false);
        threadTurnStatusView.setMaxLines(3);
        threadTurnStatusView.setPadding(0, dp(4), 0, 0);
        composer.addView(threadTurnStatusView, matchWrap());

        queuedTurnListLayout = new LinearLayout(this);
        queuedTurnListLayout.setOrientation(LinearLayout.VERTICAL);
        queuedTurnListLayout.setPadding(0, dp(6), 0, 0);
        queuedTurnListLayout.setVisibility(View.GONE);
        composer.addView(queuedTurnListLayout, matchWrap());

        return composer;
    }

    private LinearLayout buildThreadResponseOverlay() {
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(12), dp(10), dp(12), dp(10));
        overlay.setBackground(outlineDrawable(COLOR_PANEL, COLOR_BORDER, dp(8)));
        overlay.setElevation(dp(10));
        overlay.setVisibility(View.GONE);
        overlay.setOnClickListener(view -> {
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        threadResponseText = text("", 13, COLOR_INK, Typeface.NORMAL);
        threadResponseText.setLineSpacing(dp(3), 1.0f);
        threadResponseText.setMaxLines(16);
        row.addView(threadResponseText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(spacer(dp(8)));

        Button closeButton = toolbarButton("X", Color.WHITE, COLOR_MUTED);
        closeButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
        closeButton.setOnClickListener(view -> hideThreadResponseOverlay());
        row.addView(closeButton, new LinearLayout.LayoutParams(dp(44), dp(38)));

        overlay.addView(row, matchWrap());
        return overlay;
    }

    private LinearLayout buildCatalogSection() {
        LinearLayout section = section();
        catalogSectionLayout = section;
        section.addView(sectionTitle("Codex Desktop"));

        LinearLayout hostRow = new LinearLayout(this);
        hostRow.setOrientation(LinearLayout.HORIZONTAL);
        hostRow.setGravity(Gravity.CENTER);

        loadCatalogButton = button("Load Chats", COLOR_GOLD, COLOR_INK);
        loadCatalogButton.setOnClickListener(view -> loadCatalog());
        hostRow.addView(loadCatalogButton, weightedButton());

        hostRow.addView(spacer(dp(10)));

        Button hostStatusButton = button("Host", Color.WHITE, COLOR_PRIMARY);
        hostStatusButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
        hostStatusButton.setOnClickListener(view -> checkHostStatus());
        hostRow.addView(hostStatusButton, weightedButton());

        section.addView(hostRow, matchWrap());

        catalogStatusView = text("No catalog loaded.", 14, COLOR_MUTED, Typeface.NORMAL);
        catalogStatusView.setPadding(0, dp(12), 0, dp(8));
        section.addView(catalogStatusView);

        catalogView = text("", 13, COLOR_MUTED, Typeface.NORMAL);
        catalogView.setLineSpacing(dp(3), 1.0f);
        catalogView.setPadding(0, 0, 0, dp(8));
        catalogView.setVisibility(View.GONE);
        section.addView(catalogView);

        chatFilterInput = input("Search chats", false);
        chatFilterInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        chatFilterInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                renderFilteredChatRows(text == null ? "" : text.toString());
            }

            @Override
            public void afterTextChanged(Editable text) {
            }
        });
        section.addView(chatFilterInput, matchWrap());
        chatFilterSpacer = spacer(dp(10));
        section.addView(chatFilterSpacer);

        chatSortSpinner = new Spinner(this);
        ArrayAdapter<String> sortAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new String[]{"Pinned + Recent", "Recent", "Name A-Z", "Name Z-A"});
        sortAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        chatSortSpinner.setAdapter(sortAdapter);
        chatSortSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (chatListLayout != null) {
                    renderFilteredChatRows(chatFilterInput == null ? "" : chatFilterInput.getText().toString());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        section.addView(chatSortSpinner, matchWrap());
        chatSortSpacer = spacer(dp(10));
        section.addView(chatSortSpacer);

        chatListLayout = new LinearLayout(this);
        chatListLayout.setOrientation(LinearLayout.VERTICAL);
        section.addView(chatListLayout, matchWrap());

        messageListLayout = new LinearLayout(this);
        messageListLayout.setOrientation(LinearLayout.VERTICAL);
        messageListLayout.setPadding(0, dp(8), 0, 0);
        section.addView(messageListLayout, matchWrap());

        return section;
    }

    private LinearLayout buildSendSection() {
        LinearLayout section = section();
        section.addView(sectionTitle("Payload"));

        contentInput = input("Paste a URL, selected text, or browser share here", true);
        contentInput.setMinLines(6);
        contentInput.setGravity(Gravity.TOP | Gravity.START);
        contentInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        section.addView(contentInput, matchWrap());

        section.addView(spacer(dp(12)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button pasteButton = button("Paste", Color.WHITE, COLOR_PRIMARY);
        pasteButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
        pasteButton.setOnClickListener(view -> pasteFromClipboard());
        row.addView(pasteButton, weightedButton());

        row.addView(spacer(dp(10)));

        sendButton = button("Send", COLOR_ACCENT, Color.WHITE);
        sendButton.setOnClickListener(view -> sendCurrentPayload());
        row.addView(sendButton, weightedButton());

        section.addView(row, matchWrap());

        statusView = text("Ready", 14, COLOR_MUTED, Typeface.NORMAL);
        statusView.setPadding(0, dp(12), 0, 0);
        section.addView(statusView);

        return section;
    }

    private LinearLayout buildHistorySection() {
        LinearLayout section = section();

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = sectionTitle("Recent Sends");
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button clearButton = button("Clear", Color.TRANSPARENT, COLOR_MUTED);
        clearButton.setMinHeight(dp(36));
        clearButton.setPadding(dp(12), 0, dp(12), 0);
        clearButton.setOnClickListener(view -> clearHistory());
        header.addView(clearButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));

        section.addView(header);

        historyView = text("", 14, COLOR_MUTED, Typeface.NORMAL);
        historyView.setLineSpacing(dp(4), 1.0f);
        section.addView(historyView);

        return section;
    }

    private LinearLayout section() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(16), dp(16), dp(16), dp(16));
        section.setBackground(outlineDrawable(COLOR_PANEL, COLOR_BORDER, dp(8)));
        return section;
    }

    private TextView sectionTitle(String title) {
        TextView view = text(title, 18, COLOR_INK, Typeface.BOLD);
        view.setPadding(0, 0, 0, dp(12));
        return view;
    }

    private EditText input(String hint, boolean multiLine) {
        EditText editText = new EditText(this);
        editText.setTextColor(COLOR_INK);
        editText.setHintTextColor(Color.rgb(133, 138, 135));
        editText.setTextSize(15);
        editText.setSingleLine(!multiLine);
        editText.setHint(hint);
        editText.setPadding(dp(12), dp(10), dp(12), dp(10));
        editText.setBackground(outlineDrawable(Color.rgb(252, 251, 248), COLOR_BORDER, dp(8)));
        return editText;
    }

    private Button button(String label, int backgroundColor, int textColor) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(textColor);
        button.setMinHeight(dp(48));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(outlineDrawable(backgroundColor, backgroundColor == Color.TRANSPARENT ? Color.TRANSPARENT : backgroundColor, dp(8)));
        return button;
    }

    private Button toolbarButton(String label, int backgroundColor, int textColor) {
        Button button = button(label, backgroundColor, textColor);
        button.setTextSize(11);
        button.setSingleLine(true);
        button.setMinHeight(dp(34));
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(color);
        textView.setTypeface(Typeface.DEFAULT, style);
        return textView;
    }

    private View spacer(int size) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightedButton() {
        return new LinearLayout.LayoutParams(0, dp(48), 1f);
    }

    private LinearLayout.LayoutParams weightedToolbarButton() {
        return new LinearLayout.LayoutParams(0, dp(34), 1f);
    }

    private GradientDrawable outlineDrawable(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (stroke != Color.TRANSPARENT) {
            drawable.setStroke(dp(1), stroke);
        }
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void restoreSavedState() {
        String legacyEndpoint = preferences.getString(PREF_ENDPOINT, "");
        String legacyToken = preferences.getString(PREF_TOKEN, "");
        if (preferences.getString(PREF_LOCAL_ENDPOINT, "").isEmpty() && !legacyEndpoint.isEmpty()) {
            preferences.edit()
                    .putString(PREF_LOCAL_ENDPOINT, legacyEndpoint)
                    .putString(PREF_LOCAL_TOKEN, legacyToken)
                    .apply();
        }
        currentConnectionMode = preferences.getString(PREF_CONNECTION_MODE, MODE_LOCAL);
        if (!MODE_WEB.equals(currentConnectionMode)) {
            currentConnectionMode = MODE_LOCAL;
        }
        applyingConnectionMode = true;
        connectionModeSpinner.setSelection(MODE_WEB.equals(currentConnectionMode) ? 1 : 0);
        applyingConnectionMode = false;
        applyConnectionFieldsForMode(currentConnectionMode);
        updateHistoryView();
    }

    private void saveSettings() {
        String endpoint = normalizedEndpoint();
        endpointInput.setText(endpoint);
        String token = tokenInput.getText().toString().trim();
        SharedPreferences.Editor editor = preferences.edit()
                .putString(PREF_ENDPOINT, endpoint)
                .putString(PREF_TOKEN, token)
                .putString(PREF_CONNECTION_MODE, currentConnectionMode);
        if (MODE_WEB.equals(currentConnectionMode)) {
            editor.putString(PREF_WEB_ENDPOINT, endpoint)
                    .putString(PREF_WEB_TOKEN, token);
        } else {
            editor.putString(PREF_LOCAL_ENDPOINT, endpoint)
                    .putString(PREF_LOCAL_TOKEN, token);
        }
        editor.apply();
        setStatus("Saved " + connectionModeLabel() + " settings.", false);
    }

    private void saveConnectionFieldsForMode(String mode) {
        if (endpointInput == null || tokenInput == null) {
            return;
        }
        String endpoint = normalizedEndpoint();
        String token = tokenInput.getText().toString().trim();
        SharedPreferences.Editor editor = preferences.edit();
        if (MODE_WEB.equals(mode)) {
            editor.putString(PREF_WEB_ENDPOINT, endpoint)
                    .putString(PREF_WEB_TOKEN, token);
        } else {
            editor.putString(PREF_LOCAL_ENDPOINT, endpoint)
                    .putString(PREF_LOCAL_TOKEN, token);
        }
        editor.apply();
    }

    private void applyConnectionFieldsForMode(String mode) {
        if (endpointInput == null || tokenInput == null) {
            return;
        }
        String endpoint;
        String token;
        if (MODE_WEB.equals(mode)) {
            endpoint = preferences.getString(PREF_WEB_ENDPOINT, DEFAULT_WEB_ENDPOINT);
            token = preferences.getString(PREF_WEB_TOKEN, DEFAULT_WEB_TOKEN);
            if (endpoint == null || endpoint.isEmpty()) {
                endpoint = DEFAULT_WEB_ENDPOINT;
            }
            if (token == null || token.isEmpty()) {
                token = DEFAULT_WEB_TOKEN;
            }
        } else {
            endpoint = preferences.getString(PREF_LOCAL_ENDPOINT, preferences.getString(PREF_ENDPOINT, ""));
            token = preferences.getString(PREF_LOCAL_TOKEN, preferences.getString(PREF_TOKEN, ""));
        }
        endpointInput.setText(endpoint == null ? "" : endpoint);
        tokenInput.setText(token == null ? "" : token);
        setStatus("Using " + connectionModeLabel() + ".", false);
    }

    private String connectionModeLabel() {
        return MODE_WEB.equals(currentConnectionMode) ? "Web Link" : "Local Windows";
    }

    private void pasteFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            setStatus("Clipboard is empty.", true);
            return;
        }

        ClipData clipData = clipboard.getPrimaryClip();
        if (clipData == null || clipData.getItemCount() == 0) {
            setStatus("Clipboard is empty.", true);
            return;
        }

        CharSequence text = clipData.getItemAt(0).coerceToText(this);
        if (text == null || text.toString().trim().isEmpty()) {
            setStatus("Clipboard has no text.", true);
            return;
        }

        contentInput.setText(text.toString());
        contentInput.setSelection(contentInput.length());
        setStatus("Clipboard text loaded.", false);
    }

    private void loadCatalog() {
        String endpoint = normalizedEndpoint();
        String token = tokenInput.getText().toString().trim();

        if (endpoint.isEmpty()) {
            setCatalogStatus("Enter the desktop endpoint URL first.", true);
            endpointInput.requestFocus();
            return;
        }

        saveSettings();
        loadCatalogButton.setEnabled(false);
        setCatalogStatus("Loading Codex desktop catalog...", false);

        networkExecutor.execute(() -> {
            try {
                String catalogEndpoint = catalogEndpointFor(endpoint);
                Log.i(TAG, "Loading catalog from " + catalogEndpoint);
                String body = getCatalog(catalogEndpoint, token);
                JSONObject catalog = new JSONObject(body);
                mainHandler.post(() -> {
                    loadCatalogButton.setEnabled(true);
                    resetThreadView();
                    JSONArray chats = catalog.optJSONArray("chats");
                    loadedCatalogChats = chats == null ? new JSONArray() : chats;
                    loadedCatalogChatCount = loadedCatalogChats.length();
                    hasLoadedCatalog = true;
                    renderFilteredChatRows(chatFilterInput == null ? "" : chatFilterInput.getText().toString());
                    catalogView.setText("");
                    catalogView.setVisibility(View.GONE);
                });
            } catch (Exception error) {
                Log.e(TAG, "Catalog load failed", error);
                mainHandler.post(() -> {
                    loadCatalogButton.setEnabled(true);
                    setCatalogStatus(error.getMessage() == null ? "Catalog load failed." : error.getMessage(), true);
                });
            }
        });
    }

    private String catalogEndpointFor(String endpoint) throws IOException {
        URL url = new URL(endpoint);
        String path = url.getPath();
        String catalogPath;

        if (path == null || path.isEmpty() || "/".equals(path)) {
            catalogPath = "/catalog";
        } else {
            int lastSlash = path.lastIndexOf('/');
            String lastSegment = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            if ("link".equals(lastSegment)) {
                catalogPath = path.substring(0, lastSlash + 1) + "catalog";
            } else if (path.endsWith("/")) {
                catalogPath = path + "catalog";
            } else {
                catalogPath = path + "/catalog";
            }
        }

        return new URL(url.getProtocol(), url.getHost(), url.getPort(), catalogPath).toString();
    }

    private String healthEndpointFor(String endpoint) throws IOException {
        URL url = new URL(endpoint);
        String path = url.getPath();
        String healthPath;

        if (path == null || path.isEmpty() || "/".equals(path)) {
            healthPath = "/health";
        } else {
            int lastSlash = path.lastIndexOf('/');
            String lastSegment = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            if ("link".equals(lastSegment) || "catalog".equals(lastSegment)) {
                healthPath = path.substring(0, lastSlash + 1) + "health";
            } else if (path.endsWith("/")) {
                healthPath = path + "health";
            } else {
                healthPath = path + "/health";
            }
        }

        return new URL(url.getProtocol(), url.getHost(), url.getPort(), healthPath).toString();
    }

    private void checkHostStatus() {
        String endpoint = normalizedEndpoint();
        String token = tokenInput.getText().toString().trim();
        if (endpoint.isEmpty()) {
            setCatalogStatus("Enter the desktop endpoint URL first.", true);
            endpointInput.requestFocus();
            return;
        }

        saveSettings();
        setCatalogStatus("Checking desktop host...", false);
        networkExecutor.execute(() -> {
            try {
                String healthEndpoint = healthEndpointFor(endpoint);
                String body = getCatalog(healthEndpoint, token);
                JSONObject health = new JSONObject(body);
                String generatedAt = health.optString("generatedAt", "");
                mainHandler.post(() -> setCatalogStatus(
                        health.optBoolean("ok")
                                ? "Host online" + (generatedAt.isEmpty() ? "." : " at " + generatedAt + ".")
                                : "Host replied but did not report ok.",
                        !health.optBoolean("ok")));
            } catch (Exception error) {
                mainHandler.post(() -> setCatalogStatus(error.getMessage() == null ? "Host check failed." : error.getMessage(), true));
            }
        });
    }

    private String getCatalog(String endpoint, String token) throws IOException {
        URL url = new URL(endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }

        int status = connection.getResponseCode();
        String response = readResponse(connection, status);
        connection.disconnect();

        if (status >= 200 && status < 300) {
            return response;
        }

        if (response.isEmpty()) {
            throw new IOException("Catalog endpoint replied with HTTP " + status + ".");
        }

        throw new IOException("HTTP " + status + ": " + response);
    }

    private void buildChatRows(JSONObject catalog) {
        JSONArray chats = catalog.optJSONArray("chats");
        loadedCatalogChats = chats == null ? new JSONArray() : chats;
        loadedCatalogChatCount = loadedCatalogChats.length();
        hasLoadedCatalog = true;
        renderFilteredChatRows(chatFilterInput == null ? "" : chatFilterInput.getText().toString());
    }

    private void renderFilteredChatRows(String query) {
        chatListLayout.removeAllViews();
        if (!hasLoadedCatalog) {
            return;
        }

        String trimmedQuery = query == null ? "" : query.trim();
        String normalizedQuery = trimmedQuery.toLowerCase(Locale.US);
        String[] terms = normalizedQuery.isEmpty() ? new String[0] : normalizedQuery.split("\\s+");
        int shown = 0;

        for (JSONObject chat : sortedCatalogChats()) {
            if (!matchesChatFilter(chat, terms)) {
                continue;
            }

            addChatRow(chat);
            shown++;
        }

        if (loadedCatalogChatCount == 0) {
            setCatalogStatus("No chats found in the desktop catalog.", false);
        } else if (terms.length == 0) {
            setCatalogStatus(loadedCatalogChatCount + " chats loaded.", false);
        } else if (shown == 0) {
            setCatalogStatus("No chats match \"" + trimmedQuery + "\".", false);
        } else {
            setCatalogStatus(shown + " of " + loadedCatalogChatCount + " chats shown.", false);
        }
    }

    private ArrayList<JSONObject> sortedCatalogChats() {
        ArrayList<JSONObject> chats = new ArrayList<>();
        for (int index = 0; index < loadedCatalogChats.length(); index++) {
            JSONObject chat = loadedCatalogChats.optJSONObject(index);
            if (chat != null) {
                chats.add(chat);
            }
        }

        int sortPosition = chatSortSpinner == null ? 0 : chatSortSpinner.getSelectedItemPosition();
        Collections.sort(chats, (left, right) -> compareChats(left, right, sortPosition));
        return chats;
    }

    private int compareChats(JSONObject left, JSONObject right, int sortPosition) {
        if (sortPosition == 0) {
            int pinned = Boolean.compare(right.optBoolean("pinned"), left.optBoolean("pinned"));
            if (pinned != 0) {
                return pinned;
            }
            return Long.compare(jsonLong(right, "updatedAtMs"), jsonLong(left, "updatedAtMs"));
        }
        if (sortPosition == 2 || sortPosition == 3) {
            String leftTitle = chatDisplayTitle(left).toLowerCase(Locale.US);
            String rightTitle = chatDisplayTitle(right).toLowerCase(Locale.US);
            int result = leftTitle.compareTo(rightTitle);
            return sortPosition == 3 ? -result : result;
        }
        return Long.compare(jsonLong(right, "updatedAtMs"), jsonLong(left, "updatedAtMs"));
    }

    private boolean matchesChatFilter(JSONObject chat, String[] terms) {
        if (terms.length == 0) {
            return true;
        }

        String searchable = searchableChatText(chat);
        for (String term : terms) {
            if (!term.isEmpty() && !searchable.contains(term)) {
                return false;
            }
        }
        return true;
    }

    private String searchableChatText(JSONObject chat) {
        StringBuilder builder = new StringBuilder();
        String[] keys = {
                "id",
                "displayTitle",
                "name",
                "title",
                "originalTitle",
                "updatedAt",
                "projectLabel",
                "projectPath",
                "cwd",
                "path",
                "source",
                "threadSource",
                "status"
        };
        for (String key : keys) {
            appendSearchText(builder, jsonString(chat, key));
        }

        JSONObject project = chat.optJSONObject("project");
        if (project != null) {
            appendSearchText(builder, jsonString(project, "label"));
            appendSearchText(builder, jsonString(project, "name"));
            appendSearchText(builder, jsonString(project, "path"));
        }

        return builder.toString().toLowerCase(Locale.US);
    }

    private void appendSearchText(StringBuilder builder, String value) {
        if (value != null && !value.isEmpty()) {
            builder.append(' ').append(value);
        }
    }

    private void addChatRow(JSONObject chat) {
        String threadId = chat.optString("id");
        String title = chatDisplayTitle(chat);
        String updatedAt = formatChatTimestamp(chat);
        String projectLabel = jsonString(chat, "projectLabel");
        boolean pinned = chat.optBoolean("pinned");
        boolean active = chat.optBoolean("active");
        Button row = button(formatChatRow(title, updatedAt, projectLabel, pinned, active), Color.WHITE, active ? COLOR_PRIMARY : COLOR_INK);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        row.setSingleLine(false);
        row.setMaxLines(4);
        row.setEnabled(!threadId.isEmpty());
        row.setBackground(outlineDrawable(Color.rgb(252, 251, 248), COLOR_BORDER, dp(8)));
        row.setOnClickListener(view -> loadThread(threadId, title));
        chatListLayout.addView(row, matchWrap());
        chatListLayout.addView(spacer(dp(8)));
    }

    private String chatDisplayTitle(JSONObject chat) {
        String title = jsonString(chat, "displayTitle");
        if (title.isEmpty()) {
            title = jsonString(chat, "name");
        }
        if (title.isEmpty()) {
            title = chat.optString("title", "Untitled chat");
        }
        return title == null || title.isEmpty() ? "Untitled chat" : title;
    }

    private String formatChatTimestamp(JSONObject chat) {
        long updatedAtMs = jsonLong(chat, "updatedAtMs");
        if (updatedAtMs > 0) {
            return timeFormat.format(new Date(updatedAtMs));
        }
        String updatedAt = jsonString(chat, "updatedAt");
        return updatedAt == null ? "" : updatedAt;
    }

    private String formatChatRow(String title, String updatedAt, String projectLabel, boolean pinned, boolean active) {
        StringBuilder builder = new StringBuilder();
        if (active) {
            builder.append("Running - ");
        }
        if (pinned) {
            builder.append("Pinned - ");
        }
        builder.append(title == null || title.isEmpty() ? "Untitled chat" : title);
        if (projectLabel != null && !projectLabel.isEmpty()) {
            builder.append("\n").append(projectLabel);
        }
        if (updatedAt != null && !updatedAt.isEmpty()) {
            builder.append("\n").append(updatedAt);
        }
        return builder.toString();
    }

    private void loadThread(String threadId, String title) {
        if (currentThreadId != null && !currentThreadId.equals(threadId)) {
            unloadThreadQueue();
            stopThreadPoll();
            threadActionsExpanded = false;
            currentThreadFullLoaded = false;
        }
        loadThreadPage(threadId, title, null, false, false);
    }

    private void refreshCurrentThread() {
        if (currentThreadId == null || currentThreadId.isEmpty()) {
            return;
        }
        loadThreadPage(currentThreadId, currentThreadTitle, null, currentThreadFullLoaded, false);
    }

    private void loadOlderThreadMessages() {
        if (currentThreadId == null || currentThreadId.isEmpty() || !hasMoreThreadMessages) {
            return;
        }
        loadThreadPage(currentThreadId, currentThreadTitle, loadedRangeStart, false, true);
    }

    private void loadFullThread() {
        if (currentThreadId == null || currentThreadId.isEmpty()) {
            return;
        }
        loadThreadPage(currentThreadId, currentThreadTitle, null, true, false);
    }

    private void loadThreadPage(String threadId, String title, Integer before, boolean full, boolean prepend) {
        String endpoint = normalizedEndpoint();
        String token = tokenInput.getText().toString().trim();

        if (threadId == null || threadId.isEmpty()) {
            setCatalogStatus("This chat does not have a thread id.", true);
            return;
        }

        saveSettings();
        isLoadingThreadPage = true;
        loadCatalogButton.setEnabled(false);
        setCatalogStatus(prepend ? "Loading older messages..." : "Loading " + title + "...", false);

        networkExecutor.execute(() -> {
            try {
                String threadEndpoint = threadEndpointFor(endpoint, threadId, before, full);
                Log.i(TAG, "Loading thread from " + threadEndpoint);
                String body = getCatalog(threadEndpoint, token);
                JSONObject response = new JSONObject(body);
                mainHandler.post(() -> {
                    isLoadingThreadPage = false;
                    loadCatalogButton.setEnabled(true);
                    renderThreadPage(endpoint, response, prepend, full);
                    setCatalogStatus(full ? "Loaded full chat." : "Loaded newest chat window.", false);
                });
            } catch (Exception error) {
                Log.e(TAG, "Thread load failed", error);
                mainHandler.post(() -> {
                    isLoadingThreadPage = false;
                    loadCatalogButton.setEnabled(true);
                    setCatalogStatus(error.getMessage() == null ? "Chat load failed." : error.getMessage(), true);
                });
            }
        });
    }

    private String threadEndpointFor(String endpoint, String threadId, Integer before, boolean full) throws IOException {
        URL url = new URL(endpoint);
        String basePath = apiBasePathFor(url);
        String encodedThreadId = URLEncoder.encode(threadId, StandardCharsets.UTF_8.name());
        StringBuilder query = new StringBuilder();
        if (full) {
            query.append("?full=1");
        } else {
            query.append("?limit=").append(THREAD_PAGE_SIZE);
            if (before != null) {
                query.append("&before=").append(before);
            }
        }
        return new URL(url.getProtocol(), url.getHost(), url.getPort(), basePath + "threads/" + encodedThreadId + query).toString();
    }

    private String threadTurnsEndpointFor(String endpoint, String threadId) throws IOException {
        URL url = new URL(endpoint);
        String basePath = apiBasePathFor(url);
        String encodedThreadId = URLEncoder.encode(threadId, StandardCharsets.UTF_8.name());
        return new URL(url.getProtocol(), url.getHost(), url.getPort(), basePath + "threads/" + encodedThreadId + "/turns").toString();
    }

    private String threadsCollectionEndpointFor(String endpoint) throws IOException {
        URL url = new URL(endpoint);
        return new URL(url.getProtocol(), url.getHost(), url.getPort(), apiBasePathFor(url) + "threads").toString();
    }

    private String threadActionEndpointFor(String endpoint, String threadId, String action) throws IOException {
        URL url = new URL(endpoint);
        String encodedThreadId = URLEncoder.encode(threadId, StandardCharsets.UTF_8.name());
        return new URL(url.getProtocol(), url.getHost(), url.getPort(), apiBasePathFor(url) + "threads/" + encodedThreadId + "/" + action).toString();
    }

    private String apiBasePathFor(URL url) {
        String path = url.getPath();

        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "/";
        } else {
            int lastSlash = path.lastIndexOf('/');
            String lastSegment = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            if ("link".equals(lastSegment) || "catalog".equals(lastSegment)) {
                return path.substring(0, lastSlash + 1);
            } else if (path.endsWith("/")) {
                return path;
            }
            return path + "/";
        }
    }

    private String formatCatalog(JSONObject catalog) throws JSONException {
        JSONArray projects = catalog.optJSONArray("projects");
        JSONArray chats = catalog.optJSONArray("chats");
        int projectCount = projects == null ? 0 : projects.length();
        int chatCount = chats == null ? 0 : chats.length();
        JSONObject counts = catalog.optJSONObject("counts");
        int hiddenSubagents = counts == null ? 0 : counts.optInt("hiddenSubagentChats", 0);

        StringBuilder builder = new StringBuilder();
        builder.append(projectCount).append(" projects tracked. ");
        builder.append(chatCount).append(" chats in the main list.");
        if (hiddenSubagents > 0) {
            builder.append(" Hidden subagent chats: ").append(hiddenSubagents).append(".");
        }
        return builder.toString();
    }

    private void renderThreadPage(String endpoint, JSONObject response, boolean prepend, boolean full) {
        JSONObject thread = response.optJSONObject("thread");
        JSONArray messages = response.optJSONArray("messages");
        int previousHeight = messageListLayout.getHeight();
        int previousScrollY = rootScrollView.getScrollY();
        int previousRangeEnd = loadedRangeEnd;

        if (thread != null) {
            currentThreadId = thread.optString("id");
            currentThreadTitle = thread.optString("title", "Untitled chat");
            currentThreadCwd = thread.optString("cwd", "");
            currentThreadActive = thread.optBoolean("active");
            currentThreadActiveTurnId = thread.optString("activeTurnId", "");
            ensureQueueLoadedForCurrentThread();
        }

        loadedRangeStart = response.optInt("rangeStart", 0);
        loadedRangeEnd = prepend ? previousRangeEnd : response.optInt("rangeEnd", loadedRangeEnd);
        totalThreadMessages = response.optInt("totalMessageCount", totalThreadMessages);
        hasMoreThreadMessages = response.optBoolean("hasMoreBefore", false);
        currentThreadFullLoaded = full;

        chatListLayout.removeAllViews();
        setCatalogThreadMode(true);
        renderThreadControls(full);
        catalogView.setVisibility(View.VISIBLE);
        catalogView.setText(threadHeaderText(full));
        loadedThreadMessages = prepend
                ? prependMessages(messages, loadedThreadMessages)
                : copyMessages(messages);

        renderThreadMessages(endpoint);
        if (currentThreadActive) {
            setThreadTurnStatus("Codex is still processing this chat. New messages will queue and send in order.", false);
        } else if (!isSendingThreadTurn) {
            fastPollRemaining = 0;
            setThreadTurnStatus("", false);
        }
        renderQueuedTurns();
        if (currentThreadActive || isSendingThreadTurn || !queuedThreadTurns.isEmpty()) {
            scheduleThreadPoll();
        } else {
            stopThreadPoll();
        }

        rootScrollView.post(() -> {
            if (prepend) {
                int addedHeight = messageListLayout.getHeight() - previousHeight;
                rootScrollView.scrollTo(0, Math.max(0, previousScrollY + addedHeight));
            } else {
                scrollThreadToBottom(false);
                if (!currentThreadActive) {
                    maybeRunQueuedTurn();
                }
            }
        });
    }

    private void renderThreadMessages(String endpoint) {
        messageListLayout.removeAllViews();
        renderMergedMessages(endpoint);
        renderInlinePendingTurns(endpoint);
        updateThreadSearchMatches();
    }

    private void rerenderCurrentThreadMessages() {
        if (messageListLayout == null || currentThreadId == null || currentThreadId.isEmpty()) {
            return;
        }
        renderThreadMessages(normalizedEndpoint());
    }

    private JSONArray copyMessages(JSONArray messages) {
        JSONArray copy = new JSONArray();
        if (messages == null) {
            return copy;
        }
        for (int index = 0; index < messages.length(); index++) {
            copy.put(messages.opt(index));
        }
        return copy;
    }

    private JSONArray prependMessages(JSONArray olderMessages, JSONArray existingMessages) {
        JSONArray combined = new JSONArray();
        if (olderMessages != null) {
            for (int index = 0; index < olderMessages.length(); index++) {
                combined.put(olderMessages.opt(index));
            }
        }
        if (existingMessages != null) {
            for (int index = 0; index < existingMessages.length(); index++) {
                combined.put(existingMessages.opt(index));
            }
        }
        return combined;
    }

    private void renderMergedMessages(String endpoint) {
        String activeRole = null;
        StringBuilder activeBody = new StringBuilder();
        JSONArray activeMedia = new JSONArray();
        renderedMessageViews.clear();
        renderedMessageTexts.clear();

        for (int index = 0; index < loadedThreadMessages.length(); index++) {
            JSONObject message = loadedThreadMessages.optJSONObject(index);
            if (message == null) {
                continue;
            }

            String role = message.optString("role");
            if (!"assistant".equals(role) && !"user".equals(role)) {
                continue;
            }

            if (activeRole != null && !activeRole.equals(role)) {
                addRenderedMessage(endpoint, activeRole, activeBody.toString(), activeMedia);
                activeBody = new StringBuilder();
                activeMedia = new JSONArray();
            }

            activeRole = role;
            String body = message.optString("text").trim();
            if (!body.isEmpty()) {
                if (activeBody.length() > 0) {
                    activeBody.append("\n\n");
                }
                activeBody.append(body);
            }
            appendMedia(activeMedia, message.optJSONArray("media"));
        }

        if (activeRole != null) {
            addRenderedMessage(endpoint, activeRole, activeBody.toString(), activeMedia);
        }
    }

    private void renderInlinePendingTurns(String endpoint) {
        if (sendingThreadPayload != null && !payloadIsQueued(sendingThreadPayload)) {
            addRenderedPendingMessage(endpoint, sendingThreadPayload, "Sending");
        }

        for (QueuedTurn turn : new ArrayList<>(queuedThreadTurns)) {
            String status = samePayload(turn.payload, sendingThreadPayload) ? "Sending queued" : "Queued";
            addRenderedPendingMessage(endpoint, turn.payload, status);
        }
    }

    private void addRenderedPendingMessage(String endpoint, JSONObject payload, String status) {
        View messageView = buildMessageView(endpoint, "user", pendingBodyForPayload(payload, status), new JSONArray());
        messageListLayout.addView(messageView);
        renderedMessageViews.add(messageView);
        renderedMessageTexts.add(("user\n" + pendingBodyForPayload(payload, status)).toLowerCase(Locale.US));
    }

    private String pendingBodyForPayload(JSONObject payload, String status) {
        String prompt = payload == null ? "" : payload.optString("prompt", "").trim();
        JSONArray images = payload == null ? null : payload.optJSONArray("images");
        int imageCount = images == null ? 0 : images.length();
        StringBuilder builder = new StringBuilder();
        builder.append("[").append(status).append(" from phone]").append("\n\n");
        builder.append(prompt.isEmpty() ? "Image-only message" : prompt);
        if (imageCount > 0) {
            builder.append("\n\n").append(imageCount).append(imageCount == 1 ? " image attached" : " images attached");
        }
        return builder.toString();
    }

    private boolean payloadIsQueued(JSONObject payload) {
        for (QueuedTurn turn : queuedThreadTurns) {
            if (samePayload(turn.payload, payload)) {
                return true;
            }
        }
        return false;
    }

    private boolean samePayload(JSONObject left, JSONObject right) {
        if (left == null || right == null) {
            return false;
        }
        long leftSentAt = left.optLong("sentAt", -1L);
        long rightSentAt = right.optLong("sentAt", -2L);
        if (leftSentAt > 0 && leftSentAt == rightSentAt) {
            return true;
        }
        boolean leftHasImages = left.optJSONArray("images") != null;
        boolean rightHasImages = right.optJSONArray("images") != null;
        return left.optString("prompt", "").equals(right.optString("prompt", ""))
                && leftHasImages == rightHasImages;
    }

    private void addRenderedMessage(String endpoint, String role, String body, JSONArray media) {
        View messageView = buildMessageView(endpoint, role, body, media);
        messageListLayout.addView(messageView);
        renderedMessageViews.add(messageView);
        renderedMessageTexts.add((role + "\n" + body).toLowerCase(Locale.US));
    }

    private void updateThreadSearchMatches() {
        threadSearchMatches.clear();
        String query = currentThreadSearchQuery == null ? "" : currentThreadSearchQuery.trim().toLowerCase(Locale.US);
        if (query.isEmpty()) {
            currentThreadSearchMatch = -1;
            return;
        }

        for (int index = 0; index < renderedMessageTexts.size(); index++) {
            if (renderedMessageTexts.get(index).contains(query)) {
                threadSearchMatches.add(index);
            }
        }
        if (threadSearchMatches.isEmpty()) {
            currentThreadSearchMatch = -1;
        } else if (currentThreadSearchMatch >= threadSearchMatches.size()) {
            currentThreadSearchMatch = threadSearchMatches.size() - 1;
        }
    }

    private void jumpToThreadSearchMatch(int direction) {
        updateThreadSearchMatches();
        if (threadSearchMatches.isEmpty()) {
            if (!currentThreadSearchQuery.trim().isEmpty()) {
                setThreadTurnStatus("No matches in loaded messages.", false);
            }
            return;
        }

        if (currentThreadSearchMatch < 0) {
            currentThreadSearchMatch = direction < 0 ? threadSearchMatches.size() - 1 : 0;
        } else {
            currentThreadSearchMatch += direction;
            if (currentThreadSearchMatch < 0) {
                currentThreadSearchMatch = threadSearchMatches.size() - 1;
            } else if (currentThreadSearchMatch >= threadSearchMatches.size()) {
                currentThreadSearchMatch = 0;
            }
        }

        int messageIndex = threadSearchMatches.get(currentThreadSearchMatch);
        if (messageIndex >= 0 && messageIndex < renderedMessageViews.size()) {
            View target = renderedMessageViews.get(messageIndex);
            rootScrollView.post(() -> rootScrollView.smoothScrollTo(0, Math.max(0, scrollYFor(target) - dp(8))));
            setThreadTurnStatus("Match " + (currentThreadSearchMatch + 1) + " of " + threadSearchMatches.size(), false);
        }
    }

    private void appendMedia(JSONArray target, JSONArray source) {
        if (source == null) {
            return;
        }
        for (int index = 0; index < source.length(); index++) {
            target.put(source.opt(index));
        }
    }

    private String threadHeaderText(boolean full) {
        String title = currentThreadTitle == null || currentThreadTitle.isEmpty() ? "Untitled chat" : currentThreadTitle;
        return title + "\n" + currentThreadId + "\n\n" + threadScopeText(full);
    }

    private String threadScopeText(boolean full) {
        return full
                ? "Full chat loaded"
                : "Showing messages " + Math.max(1, loadedRangeStart + 1) + "-" + loadedRangeEnd + " of " + totalThreadMessages;
    }

    private String fixedThreadSummaryText(boolean full) {
        String title = currentThreadTitle == null || currentThreadTitle.isEmpty() ? "Untitled chat" : currentThreadTitle;
        return title + "\n" + threadScopeText(full);
    }

    private void renderThreadControls(boolean full) {
        threadControlsLayout.removeAllViews();
        threadControlsLayout.setVisibility(View.VISIBLE);

        TextView summaryView = text(fixedThreadSummaryText(full), 12, COLOR_INK, Typeface.BOLD);
        summaryView.setMaxLines(2);
        summaryView.setLineSpacing(dp(1), 1.0f);
        summaryView.setPadding(0, 0, 0, dp(4));
        threadControlsLayout.addView(summaryView, matchWrap());

        threadControlsLayout.addView(buildPrimaryThreadControlRow(full), matchWrap());
        if (threadActionsExpanded) {
            threadControlsLayout.addView(buildThreadNavigationRow(), matchWrap());
            threadControlsLayout.addView(buildThreadActionRow(), matchWrap());
            threadControlsLayout.addView(buildProjectActionRow(), matchWrap());
            threadControlsLayout.addView(buildThreadSearchRow(), matchWrap());
        }
        if (threadComposerLayout != null) {
            threadControlsLayout.addView(threadComposerLayout, matchWrap());
            threadComposerLayout.setVisibility(View.VISIBLE);
            threadSendButton.setEnabled(true);
            threadSendButton.setText(currentThreadActive || isSendingThreadTurn ? "Queue" : "Send");
            threadPromptInput.setHint(currentThreadActive
                    ? "Queue a message for this chat"
                    : "Message Codex in this chat");
            renderQueuedTurns();
        }
    }

    private LinearLayout buildPrimaryThreadControlRow(boolean full) {
        LinearLayout row = compactToolbarRow();
        row.setPadding(0, 0, 0, 0);

        Button refreshButton = toolbarButton("Refresh", Color.WHITE, currentThreadId == null || currentThreadId.isEmpty() ? COLOR_MUTED : COLOR_PRIMARY);
        refreshButton.setEnabled(currentThreadId != null && !currentThreadId.isEmpty());
        refreshButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
        refreshButton.setOnClickListener(view -> refreshCurrentThread());
        row.addView(refreshButton, new LinearLayout.LayoutParams(0, dp(32), 1.15f));

        row.addView(spacer(dp(3)));

        Button bottomButton = toolbarButton("End", COLOR_GOLD, COLOR_INK);
        bottomButton.setOnClickListener(view -> scrollToThreadBottom());
        row.addView(bottomButton, new LinearLayout.LayoutParams(0, dp(32), 0.85f));

        row.addView(spacer(dp(3)));

        Button actionsButton = toolbarButton(threadActionsExpanded ? "Actions -" : "Actions +", Color.WHITE, COLOR_PRIMARY);
        actionsButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
        actionsButton.setOnClickListener(view -> {
            threadActionsExpanded = !threadActionsExpanded;
            renderThreadControls(full);
        });
        row.addView(actionsButton, new LinearLayout.LayoutParams(0, dp(32), 1.15f));

        return row;
    }

    private LinearLayout buildThreadNavigationRow() {
        LinearLayout row = compactToolbarRow();

        Button listButton = toolbarButton("Chats", Color.WHITE, COLOR_PRIMARY);
        listButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
        listButton.setOnClickListener(view -> {
            loadCatalog();
            rootScrollView.post(() -> rootScrollView.smoothScrollTo(0, 0));
        });
        row.addView(listButton, weightedToolbarButton());

        row.addView(spacer(dp(3)));

        Button olderButton = toolbarButton("Older", Color.WHITE, hasMoreThreadMessages ? COLOR_PRIMARY : COLOR_MUTED);
        olderButton.setEnabled(hasMoreThreadMessages);
        olderButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
        olderButton.setOnClickListener(view -> loadOlderThreadMessages());
        row.addView(olderButton, weightedToolbarButton());

        row.addView(spacer(dp(3)));

        Button topButton = toolbarButton("Top", Color.WHITE, COLOR_PRIMARY);
        topButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
        topButton.setOnClickListener(view -> scrollToThreadTop());
        row.addView(topButton, weightedToolbarButton());

        row.addView(spacer(dp(3)));

        Button fullButton = toolbarButton("Full", Color.WHITE, COLOR_PRIMARY);
        fullButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
        fullButton.setOnClickListener(view -> loadFullThread());
        row.addView(fullButton, weightedToolbarButton());

        return row;
    }

    private LinearLayout buildThreadActionRow() {
        LinearLayout row = compactToolbarRow();

        Button newButton = toolbarButton("New", Color.WHITE, currentThreadCwd.isEmpty() ? COLOR_MUTED : COLOR_PRIMARY);
        newButton.setEnabled(!currentThreadCwd.isEmpty());
        newButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
        newButton.setOnClickListener(view -> startNewThreadFromCurrentProject());
        row.addView(newButton, weightedToolbarButton());

        row.addView(spacer(dp(3)));

        boolean canStop = currentThreadActive && !currentThreadActiveTurnId.isEmpty();
        Button stopButton = toolbarButton("Stop", Color.WHITE, canStop ? COLOR_ACCENT : COLOR_MUTED);
        stopButton.setEnabled(canStop);
        stopButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
        stopButton.setOnClickListener(view -> interruptCurrentThread());
        row.addView(stopButton, weightedToolbarButton());

        row.addView(spacer(dp(3)));

        Button statusButton = toolbarButton("Git", Color.WHITE, currentThreadCwd.isEmpty() ? COLOR_MUTED : COLOR_PRIMARY);
        statusButton.setEnabled(!currentThreadCwd.isEmpty());
        statusButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
        statusButton.setOnClickListener(view -> loadProjectStatus());
        row.addView(statusButton, weightedToolbarButton());

        row.addView(spacer(dp(3)));

        Button diffButton = toolbarButton("Diff", Color.WHITE, currentThreadCwd.isEmpty() ? COLOR_MUTED : COLOR_PRIMARY);
        diffButton.setEnabled(!currentThreadCwd.isEmpty());
        diffButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
        diffButton.setOnClickListener(view -> loadProjectDiff());
        row.addView(diffButton, weightedToolbarButton());

        return row;
    }

    private LinearLayout buildProjectActionRow() {
        LinearLayout row = compactToolbarRow();

        Button checkpointButton = toolbarButton("Checkpoint", Color.WHITE, currentThreadCwd.isEmpty() ? COLOR_MUTED : COLOR_PRIMARY);
        checkpointButton.setEnabled(!currentThreadCwd.isEmpty());
        checkpointButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
        checkpointButton.setOnClickListener(view -> createProjectCheckpoint());
        row.addView(checkpointButton, new LinearLayout.LayoutParams(0, dp(34), 1.4f));

        row.addView(spacer(dp(3)));

        Button revertButton = toolbarButton("Revert", Color.WHITE, currentThreadCwd.isEmpty() ? COLOR_MUTED : COLOR_ACCENT);
        revertButton.setEnabled(!currentThreadCwd.isEmpty());
        revertButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
        revertButton.setOnClickListener(view -> confirmAndRevertProject());
        row.addView(revertButton, weightedToolbarButton());

        return row;
    }

    private LinearLayout compactToolbarRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(3), 0, 0);
        return row;
    }

    private LinearLayout buildThreadSearchRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, 0);

        threadSearchInput = input("Search in chat", false);
        threadSearchInput.setTextSize(13);
        threadSearchInput.setSingleLine(true);
        threadSearchInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (!currentThreadSearchQuery.isEmpty()) {
            threadSearchInput.setText(currentThreadSearchQuery);
            threadSearchInput.setSelection(threadSearchInput.length());
        }
        threadSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                currentThreadSearchQuery = text == null ? "" : text.toString();
                currentThreadSearchMatch = -1;
                updateThreadSearchMatches();
                if (!threadSearchMatches.isEmpty()) {
                    jumpToThreadSearchMatch(1);
                }
            }

            @Override
            public void afterTextChanged(Editable text) {
            }
        });
        row.addView(threadSearchInput, new LinearLayout.LayoutParams(0, dp(38), 1f));

        row.addView(spacer(dp(4)));

        Button prevButton = toolbarButton("Prev", Color.WHITE, COLOR_PRIMARY);
        prevButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
        prevButton.setOnClickListener(view -> jumpToThreadSearchMatch(-1));
        row.addView(prevButton, new LinearLayout.LayoutParams(dp(58), dp(38)));

        row.addView(spacer(dp(4)));

        Button nextButton = toolbarButton("Next", Color.WHITE, COLOR_PRIMARY);
        nextButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
        nextButton.setOnClickListener(view -> jumpToThreadSearchMatch(1));
        row.addView(nextButton, new LinearLayout.LayoutParams(dp(58), dp(38)));

        return row;
    }

    private void startNewThreadFromCurrentProject() {
        String endpoint = normalizedEndpoint();
        String token = tokenInput.getText().toString().trim();
        if (endpoint.isEmpty()) {
            setThreadTurnStatus("Enter the desktop endpoint URL first.", true);
            return;
        }
        if (currentThreadCwd == null || currentThreadCwd.isEmpty()) {
            setThreadTurnStatus("This chat does not expose a project path.", true);
            return;
        }

        setThreadTurnStatus("Starting a new chat for this project...", false);
        networkExecutor.execute(() -> {
            try {
                JSONObject payload = new JSONObject().put("cwd", currentThreadCwd);
                String collectionEndpoint = threadsCollectionEndpointFor(endpoint);
                String body = postThreadPayload(collectionEndpoint, token, payload);
                JSONObject response = new JSONObject(body);
                String threadId = response.optString("threadId", "");
                if (threadId.isEmpty()) {
                    JSONObject thread = response.optJSONObject("thread");
                    threadId = thread == null ? "" : thread.optString("id", "");
                }
                if (threadId.isEmpty()) {
                    throw new IOException("Desktop endpoint did not return a new thread id.");
                }
                String newThreadId = threadId;
                mainHandler.post(() -> {
                    setThreadTurnStatus("New chat started.", false);
                    loadThread(newThreadId, "New chat");
                });
            } catch (Exception error) {
                mainHandler.post(() -> setThreadTurnStatus(error.getMessage() == null ? "Could not start chat." : error.getMessage(), true));
            }
        });
    }

    private void interruptCurrentThread() {
        String endpoint = normalizedEndpoint();
        String token = tokenInput.getText().toString().trim();
        if (currentThreadId == null || currentThreadId.isEmpty()) {
            setThreadTurnStatus("Open a chat first.", true);
            return;
        }
        if (currentThreadActiveTurnId == null || currentThreadActiveTurnId.isEmpty()) {
            setThreadTurnStatus("No active turn id is available for this chat.", true);
            return;
        }

        String threadId = currentThreadId;
        String turnId = currentThreadActiveTurnId;
        setThreadTurnStatus("Requesting stop...", false);
        networkExecutor.execute(() -> {
            try {
                JSONObject payload = new JSONObject().put("turnId", turnId);
                postThreadPayload(threadActionEndpointFor(endpoint, threadId, "interrupt"), token, payload);
                mainHandler.post(() -> {
                    fastPollRemaining = 8;
                    setThreadTurnStatus("Stop request sent.", false);
                    loadThreadPage(threadId, currentThreadTitle, null, false, false);
                });
            } catch (Exception error) {
                mainHandler.post(() -> setThreadTurnStatus(error.getMessage() == null ? "Stop failed." : error.getMessage(), true));
            }
        });
    }

    private void loadProjectStatus() {
        loadProjectInfo("project-status");
    }

    private void loadProjectDiff() {
        loadProjectInfo("diff");
    }

    private void loadProjectInfo(String action) {
        String endpoint = normalizedEndpoint();
        String token = tokenInput.getText().toString().trim();
        if (currentThreadId == null || currentThreadId.isEmpty()) {
            setThreadTurnStatus("Open a chat first.", true);
            return;
        }

        setThreadTurnStatus("Loading " + ("diff".equals(action) ? "diff" : "Git status") + "...", false);
        String threadId = currentThreadId;
        networkExecutor.execute(() -> {
            try {
                String body = getCatalog(threadActionEndpointFor(endpoint, threadId, action), token);
                JSONObject response = new JSONObject(body);
                String message = "diff".equals(action)
                        ? formatProjectDiff(response)
                        : formatProjectStatus(response);
                mainHandler.post(() -> {
                    showThreadResponseOverlay(message);
                    setThreadTurnStatus("", false);
                });
            } catch (Exception error) {
                mainHandler.post(() -> setThreadTurnStatus(error.getMessage() == null ? "Project request failed." : error.getMessage(), true));
            }
        });
    }

    private void createProjectCheckpoint() {
        String endpoint = normalizedEndpoint();
        String token = tokenInput.getText().toString().trim();
        if (currentThreadId == null || currentThreadId.isEmpty()) {
            setThreadTurnStatus("Open a chat first.", true);
            return;
        }

        String threadId = currentThreadId;
        setThreadTurnStatus("Creating checkpoint...", false);
        networkExecutor.execute(() -> {
            try {
                JSONObject payload = new JSONObject().put("label", "phone");
                String body = postThreadPayload(threadActionEndpointFor(endpoint, threadId, "checkpoint"), token, payload);
                JSONObject response = new JSONObject(body);
                mainHandler.post(() -> {
                    showThreadResponseOverlay(formatCheckpointResult(response));
                    setThreadTurnStatus("Checkpoint recorded.", false);
                });
            } catch (Exception error) {
                mainHandler.post(() -> setThreadTurnStatus(error.getMessage() == null ? "Checkpoint failed." : error.getMessage(), true));
            }
        });
    }

    private void confirmAndRevertProject() {
        new AlertDialog.Builder(this)
                .setTitle("Revert project?")
                .setMessage("This resets tracked files to the last Codex Link checkpoint for this project. Untracked files are left alone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Revert", (dialog, which) -> revertProjectToCheckpoint())
                .show();
    }

    private void revertProjectToCheckpoint() {
        String endpoint = normalizedEndpoint();
        String token = tokenInput.getText().toString().trim();
        if (currentThreadId == null || currentThreadId.isEmpty()) {
            setThreadTurnStatus("Open a chat first.", true);
            return;
        }

        String threadId = currentThreadId;
        setThreadTurnStatus("Reverting to checkpoint...", false);
        networkExecutor.execute(() -> {
            try {
                String body = postThreadPayload(threadActionEndpointFor(endpoint, threadId, "revert"), token, new JSONObject());
                JSONObject response = new JSONObject(body);
                mainHandler.post(() -> {
                    showThreadResponseOverlay(formatRevertResult(response));
                    setThreadTurnStatus("Project reverted.", false);
                    loadThreadPage(threadId, currentThreadTitle, null, false, false);
                });
            } catch (Exception error) {
                mainHandler.post(() -> setThreadTurnStatus(error.getMessage() == null ? "Revert failed." : error.getMessage(), true));
            }
        });
    }

    private String formatProjectStatus(JSONObject response) {
        if (!response.optBoolean("isGitRepo", false)) {
            return "Not a Git repo\n\n" + response.optString("cwd", "") + "\n\n" + response.optString("error", "");
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Git status");
        builder.append("\n\nBranch: ").append(response.optString("branch", "(unknown)"));
        builder.append("\nHEAD: ").append(response.optString("head", "(unknown)"));
        builder.append("\nRoot: ").append(response.optString("root", ""));
        String statusText = response.optString("statusText", "").trim();
        builder.append("\n\n").append(statusText.isEmpty() ? "Working tree clean." : statusText);
        return builder.toString();
    }

    private String formatProjectDiff(JSONObject response) {
        if (!response.optBoolean("isGitRepo", false)) {
            return "Not a Git repo\n\n" + response.optString("cwd", "") + "\n\n" + response.optString("error", "");
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Diff");
        String stat = response.optString("stat", "").trim();
        if (!stat.isEmpty()) {
            builder.append("\n\n").append(stat);
        }
        builder.append("\n\n").append(response.optString("diff", "No tracked diff."));
        if (response.optBoolean("truncated", false)) {
            builder.append("\n\nDiff was truncated on the desktop bridge.");
        }
        return builder.toString();
    }

    private String formatCheckpointResult(JSONObject response) {
        if (!response.optBoolean("ok", false)) {
            return "Checkpoint failed\n\n" + response.optString("error", "Unknown error");
        }
        StringBuilder builder = new StringBuilder();
        builder.append(response.optBoolean("createdCommit", false) ? "Checkpoint commit created" : "Checkpoint recorded");
        builder.append("\n\nHash: ").append(response.optString("checkpointShortHash", ""));
        String output = response.optString("commitOutput", "").trim();
        if (!output.isEmpty()) {
            builder.append("\n\n").append(output);
        }
        return builder.toString();
    }

    private String formatRevertResult(JSONObject response) {
        if (!response.optBoolean("ok", false)) {
            return "Revert failed\n\n" + response.optString("error", "Unknown error");
        }
        JSONObject checkpoint = response.optJSONObject("checkpoint");
        StringBuilder builder = new StringBuilder();
        builder.append("Reverted to checkpoint");
        if (checkpoint != null) {
            builder.append("\n\n").append(checkpoint.optString("label", "checkpoint"));
            builder.append("\n").append(checkpoint.optString("hash", ""));
        }
        String output = response.optString("resetOutput", "").trim();
        if (!output.isEmpty()) {
            builder.append("\n\n").append(output);
        }
        return builder.toString();
    }

    private void scrollToThreadTop() {
        rootScrollView.post(() -> rootScrollView.smoothScrollTo(0, Math.max(0, scrollYFor(catalogView))));
    }

    private void scrollToThreadBottom() {
        scrollThreadToBottom(true);
    }

    private void scrollThreadToBottom(boolean smooth) {
        rootScrollView.post(() -> {
            int target = scrollYFor(messageListLayout) + messageListLayout.getHeight() - rootScrollView.getHeight();
            if (smooth) {
                rootScrollView.smoothScrollTo(0, Math.max(0, target));
            } else {
                rootScrollView.scrollTo(0, Math.max(0, target));
            }
        });
    }

    private int scrollYFor(View target) {
        int y = 0;
        View current = target;
        while (current != null && current != rootScrollView) {
            y += current.getTop();
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) {
                break;
            }
            current = (View) parent;
        }
        return y;
    }

    private View buildMessageView(String endpoint, String role, String body, JSONArray media) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(12), 0, dp(14));

        String label = "assistant".equals(role) ? "Codex" : "You";
        TextView roleView = text(label, 13, "assistant".equals(role) ? COLOR_PRIMARY : COLOR_ACCENT, Typeface.BOLD);
        block.addView(roleView);

        if (body.length() > 3500) {
            body = truncateMessageBody(body, 3500);
        }
        if (!body.isEmpty()) {
            TextView bodyView = text(body, 15, COLOR_INK, Typeface.NORMAL);
            bodyView.setLineSpacing(dp(4), 1.0f);
            bodyView.setPadding(0, dp(5), 0, 0);
            bodyView.setTextIsSelectable(true);
            bodyView.setFocusable(true);
            bodyView.setFocusableInTouchMode(true);
            block.addView(bodyView);
        }

        if (media != null) {
            for (int index = 0; index < media.length(); index++) {
                JSONObject item = media.optJSONObject(index);
                if (item == null || !"image".equals(item.optString("kind"))) {
                    continue;
                }
                Button imageButton = button("Image: " + item.optString("label", "image"), Color.WHITE, COLOR_PRIMARY);
                imageButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                imageButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
                String loadUrl = item.optString("loadUrl");
                boolean available = item.optBoolean("available") && !loadUrl.isEmpty() && !"null".equals(loadUrl);
                imageButton.setEnabled(available);
                imageButton.setText(available
                        ? "Image: " + item.optString("label", "image") + "\nTap to load"
                        : "Image placeholder: " + item.optString("label", "image"));
                imageButton.setOnClickListener(view -> openMediaUrl(endpoint, loadUrl));
                block.addView(spacer(dp(8)));
                block.addView(imageButton, matchWrap());
            }
        }

        return block;
    }

    private String truncateMessageBody(String body, int maxChars) {
        if (body == null || body.length() <= maxChars) {
            return body == null ? "" : body;
        }

        int floor = Math.max(0, maxChars - 300);
        int cut = maxChars;
        for (int index = maxChars; index >= floor; index--) {
            if (Character.isWhitespace(body.charAt(index))) {
                cut = index;
                break;
            }
        }

        return body.substring(0, cut).trim() + "\n\n...";
    }

    private void openMediaUrl(String endpoint, String loadUrl) {
        try {
            String mediaUrl = absoluteUrlForEndpoint(endpoint, loadUrl);
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(mediaUrl));
            startActivity(intent);
        } catch (Exception error) {
            setCatalogStatus("Could not open image.", true);
        }
    }

    private String absoluteUrlForEndpoint(String endpoint, String relativePath) throws IOException {
        URL url = new URL(endpoint);
        return new URL(url.getProtocol(), url.getHost(), url.getPort(), relativePath).toString();
    }

    private void resetThreadView() {
        currentThreadId = null;
        currentThreadTitle = null;
        currentThreadCwd = "";
        currentThreadActiveTurnId = "";
        loadedRangeStart = 0;
        loadedRangeEnd = 0;
        totalThreadMessages = 0;
        hasMoreThreadMessages = false;
        fastPollRemaining = 0;
        isLoadingThreadPage = false;
        isSendingThreadTurn = false;
        currentThreadActive = false;
        threadActionsExpanded = false;
        currentThreadFullLoaded = false;
        sendingThreadPayload = null;
        loadedThreadMessages = new JSONArray();
        currentThreadSearchQuery = "";
        currentThreadSearchMatch = -1;
        threadSearchMatches.clear();
        unloadThreadQueue();
        stopThreadPoll();
        hideThreadResponseOverlay();
        setThreadTurnStatus("", false);
        setCatalogThreadMode(false);
        threadControlsLayout.removeAllViews();
        threadControlsLayout.setVisibility(View.GONE);
        messageListLayout.removeAllViews();
        catalogView.setText("");
        catalogView.setVisibility(View.GONE);
    }

    private void setCatalogThreadMode(boolean enabled) {
        if (catalogSectionLayout == null) {
            return;
        }

        if (enabled) {
            catalogSectionLayout.setPadding(0, 0, 0, 0);
            catalogSectionLayout.setBackgroundColor(COLOR_BACKGROUND);
        } else {
            catalogSectionLayout.setPadding(dp(16), dp(16), dp(16), dp(16));
            catalogSectionLayout.setBackground(outlineDrawable(COLOR_PANEL, COLOR_BORDER, dp(8)));
        }

        int supportingVisibility = enabled ? View.GONE : View.VISIBLE;
        if (loadCatalogButton != null) {
            loadCatalogButton.setVisibility(supportingVisibility);
        }
        if (catalogStatusView != null) {
            catalogStatusView.setVisibility(supportingVisibility);
        }
        if (chatFilterInput != null) {
            chatFilterInput.setVisibility(supportingVisibility);
            if (enabled) {
                chatFilterInput.clearFocus();
            }
        }
        if (chatFilterSpacer != null) {
            chatFilterSpacer.setVisibility(supportingVisibility);
        }
        if (chatSortSpinner != null) {
            chatSortSpinner.setVisibility(supportingVisibility);
        }
        if (chatSortSpacer != null) {
            chatSortSpacer.setVisibility(supportingVisibility);
        }
        if (sendSectionSpacer != null) {
            sendSectionSpacer.setVisibility(supportingVisibility);
        }
        if (sendSectionLayout != null) {
            sendSectionLayout.setVisibility(supportingVisibility);
        }
        if (historySectionSpacer != null) {
            historySectionSpacer.setVisibility(supportingVisibility);
        }
        if (historySectionLayout != null) {
            historySectionLayout.setVisibility(supportingVisibility);
        }
        if (threadComposerLayout != null) {
            threadComposerLayout.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
    }

    private void applyIncomingIntent(Intent intent, boolean showStatus) {
        Uri imageUri = extractIncomingImage(intent);
        if (imageUri != null) {
            selectedImageUri = imageUri;
            selectedImageName = displayNameForUri(imageUri);
            selectedImageMimeType = getContentResolver().getType(imageUri);
            if (selectedImageMimeType == null || selectedImageMimeType.isEmpty()) {
                selectedImageMimeType = "image/*";
            }
            updateAttachmentPreview();
            if (showStatus) {
                setThreadTurnStatus("Shared image attached.", false);
            }
        }

        String incoming = extractIncomingText(intent);
        if (incoming == null || incoming.trim().isEmpty()) {
            return;
        }

        contentInput.setText(incoming.trim());
        contentInput.setSelection(contentInput.length());
        if (showStatus) {
            setStatus("Shared text loaded.", false);
        }
    }

    private String extractIncomingText(Intent intent) {
        if (intent == null) {
            return null;
        }

        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            CharSequence extraText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (extraText != null) {
                return extraText.toString();
            }
        }

        if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            CharSequence selectedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            return selectedText == null ? null : selectedText.toString();
        }

        return null;
    }

    private Uri extractIncomingImage(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            return null;
        }
        String type = intent.getType();
        if (type == null || !type.startsWith("image/")) {
            return null;
        }
        return intent.getParcelableExtra(Intent.EXTRA_STREAM);
    }

    private void sendCurrentPayload() {
        String endpoint = normalizedEndpoint();
        String token = tokenInput.getText().toString().trim();
        String content = contentInput.getText().toString().trim();

        if (endpoint.isEmpty()) {
            setStatus("Enter the desktop endpoint URL first.", true);
            endpointInput.requestFocus();
            return;
        }

        if (content.isEmpty()) {
            setStatus("Add a URL or text payload first.", true);
            contentInput.requestFocus();
            return;
        }

        saveSettings();
        sendButton.setEnabled(false);
        setStatus("Sending...", false);

        networkExecutor.execute(() -> {
            try {
                String message = postPayload(endpoint, token, content);
                mainHandler.post(() -> {
                    sendButton.setEnabled(true);
                    addHistory(content, endpoint);
                    setStatus(message, false);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    sendButton.setEnabled(true);
                    setStatus(error.getMessage() == null ? "Send failed." : error.getMessage(), true);
                });
            }
        });
    }

    private void sendThreadPrompt() {
        String endpoint = normalizedEndpoint();
        String token = tokenInput.getText().toString().trim();
        String prompt = threadPromptInput.getText().toString().trim();
        Uri attachmentUri = selectedImageUri;
        String attachmentName = selectedImageName;
        String attachmentMimeType = selectedImageMimeType;
        JSONArray preservedImages = copyJsonArray(editingQueuedImages);
        boolean hasPreservedImages = preservedImages != null && preservedImages.length() > 0;
        boolean hasAttachment = attachmentUri != null || hasPreservedImages;

        if (currentThreadId == null || currentThreadId.isEmpty()) {
            setThreadTurnStatus("Open a chat first.", true);
            return;
        }
        if (endpoint.isEmpty()) {
            setThreadTurnStatus("Enter the desktop endpoint URL first.", true);
            return;
        }
        if (prompt.isEmpty() && !hasAttachment) {
            setThreadTurnStatus("Type a message or attach an image first.", true);
            threadPromptInput.requestFocus();
            return;
        }
        if (isSendingThreadTurn) {
            queueThreadPrompt(prompt, attachmentUri, attachmentName, attachmentMimeType, preservedImages);
            return;
        }
        if (currentThreadActive) {
            queueThreadPrompt(prompt, attachmentUri, attachmentName, attachmentMimeType, preservedImages);
            return;
        }

        saveSettings();
        isSendingThreadTurn = true;
        updateThreadComposerState();
        setThreadTurnStatus("Sending to Codex...", false);

        networkExecutor.execute(() -> {
            JSONObject payload = null;
            try {
                payload = buildThreadTurnPayload(prompt, attachmentUri, attachmentName, attachmentMimeType, preservedImages);
                JSONObject sendPayload = payload;
                mainHandler.post(() -> {
                    sendingThreadPayload = sendPayload;
                    threadPromptInput.setText("");
                    if (hasAttachment) {
                        clearSelectedImageState();
                        editingQueuedImages = null;
                        updateAttachmentPreview();
                    }
                    rerenderCurrentThreadMessages();
                    scrollThreadToBottom(true);
                });
                String turnsEndpoint = threadTurnsEndpointFor(endpoint, currentThreadId);
                Log.i(TAG, "Sending thread turn to " + turnsEndpoint);
                postThreadPayload(turnsEndpoint, token, sendPayload);
                mainHandler.post(() -> {
                    isSendingThreadTurn = false;
                    if (samePayload(sendingThreadPayload, sendPayload)) {
                        sendingThreadPayload = null;
                    }
                    updateThreadComposerState();
                    setThreadTurnStatus("Codex replied.", false);
                    loadThreadPage(currentThreadId, currentThreadTitle, null, false, false);
                });
            } catch (Exception error) {
                Log.e(TAG, "Thread turn failed", error);
                JSONObject failedPayload = payload;
                mainHandler.post(() -> {
                    isSendingThreadTurn = false;
                    if (samePayload(sendingThreadPayload, failedPayload)) {
                        sendingThreadPayload = null;
                    }
                    updateThreadComposerState();
                    if (failedPayload != null) {
                        queuePreparedThreadPayload(
                                failedPayload,
                                isConflictError(error)
                                        ? "Codex is still processing. Message preserved in the queue."
                                        : friendlyThreadError(error, "Send failed. Message preserved in the queue."),
                                !isConflictError(error));
                        return;
                    }
                    setThreadTurnStatus(friendlyThreadError(error, "Send failed."), true);
                });
            }
        });
    }

    private void queueThreadPrompt(
            String prompt,
            Uri attachmentUri,
            String attachmentName,
            String attachmentMimeType,
            JSONArray preservedImages
    ) {
        saveSettings();
        threadSendButton.setEnabled(false);
        setThreadTurnStatus("Preparing queued message...", false);

        networkExecutor.execute(() -> {
            try {
                JSONObject payload = buildThreadTurnPayload(prompt, attachmentUri, attachmentName, attachmentMimeType, preservedImages);
                mainHandler.post(() -> {
                    threadPromptInput.setText("");
                    clearSelectedImageState();
                    editingQueuedImages = null;
                    updateAttachmentPreview();
                    queuePreparedThreadPayload(
                            payload,
                            "Queued " + (queuedThreadTurns.size() + 1) + " message" + (queuedThreadTurns.size() + 1 == 1 ? "." : "s."),
                            false);
                    scheduleThreadPoll();
                    maybeRunQueuedTurn();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    updateThreadComposerState();
                    setThreadTurnStatus(friendlyThreadError(error, "Could not queue message."), true);
                });
            }
        });
    }

    private void queuePreparedThreadPayload(JSONObject payload, String statusMessage, boolean isError) {
        ensureQueueLoadedForCurrentThread();
        if (!payloadIsQueued(payload)) {
            queuedThreadTurns.add(new QueuedTurn(payload));
            persistQueue();
        }
        renderQueuedTurns();
        rerenderCurrentThreadMessages();
        scrollThreadToBottom(true);
        updateThreadComposerState();
        setThreadTurnStatus(statusMessage, isError);
    }

    private JSONObject buildThreadTurnPayload(
            String prompt,
            Uri attachmentUri,
            String attachmentName,
            String attachmentMimeType,
            JSONArray preservedImages
    ) throws IOException, JSONException {
        JSONObject payload = new JSONObject()
                .put("prompt", prompt)
                .put("sentAt", System.currentTimeMillis())
                .put("appVersion", "0.1.0");

        JSONArray images = new JSONArray();
        if (preservedImages != null) {
            for (int index = 0; index < preservedImages.length(); index++) {
                Object item = preservedImages.opt(index);
                if (item != null) {
                    images.put(item);
                }
            }
        }
        if (attachmentUri != null) {
            images.put(imagePayloadForUri(attachmentUri, attachmentName, attachmentMimeType));
        }
        if (images.length() > 0) {
            payload.put("images", images);
        }
        return payload;
    }

    private String postThreadPayload(String endpoint, String token, JSONObject payload) throws IOException {
        URL url = new URL(endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(300000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }

        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);

        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }

        int status = connection.getResponseCode();
        String response = readResponse(connection, status);
        connection.disconnect();

        if (status >= 200 && status < 300) {
            return response;
        }

        if (response.isEmpty()) {
            throw new IOException("Desktop endpoint replied with HTTP " + status + ".");
        }

        throw new IOException("HTTP " + status + ": " + response);
    }

    private void maybeRunQueuedTurn() {
        if (currentThreadActive || isSendingThreadTurn || queuedThreadTurns.isEmpty()) {
            updateThreadComposerState();
            return;
        }
        sendQueuedTurn(queuedThreadTurns.get(0));
    }

    private void sendQueuedTurn(QueuedTurn turn) {
        if (turn == null || currentThreadId == null || currentThreadId.isEmpty()) {
            return;
        }

        String endpoint = normalizedEndpoint();
        String token = tokenInput.getText().toString().trim();
        String threadId = currentThreadId;
        String threadTitle = currentThreadTitle;
        if (endpoint.isEmpty()) {
            setThreadTurnStatus("Enter the desktop endpoint URL first.", true);
            return;
        }

        isSendingThreadTurn = true;
        sendingThreadPayload = turn.payload;
        updateThreadComposerState();
        rerenderCurrentThreadMessages();
        scrollThreadToBottom(true);
        setThreadTurnStatus("Sending queued message...", false);

        networkExecutor.execute(() -> {
            try {
                String turnsEndpoint = threadTurnsEndpointFor(endpoint, threadId);
                Log.i(TAG, "Sending queued thread turn to " + turnsEndpoint);
                postThreadPayload(turnsEndpoint, token, turn.payload);
                mainHandler.post(() -> {
                    isSendingThreadTurn = false;
                    if (samePayload(sendingThreadPayload, turn.payload)) {
                        sendingThreadPayload = null;
                    }
                    queuedThreadTurns.remove(turn);
                    persistQueue();
                    renderQueuedTurns();
                    updateThreadComposerState();
                    setThreadTurnStatus("Queued message sent.", false);
                    if (threadId.equals(currentThreadId)) {
                        loadThreadPage(threadId, threadTitle, null, false, false);
                    }
                });
            } catch (Exception error) {
                Log.e(TAG, "Queued thread turn failed", error);
                mainHandler.post(() -> {
                    isSendingThreadTurn = false;
                    if (samePayload(sendingThreadPayload, turn.payload)) {
                        sendingThreadPayload = null;
                    }
                    if (isConflictError(error)) {
                        currentThreadActive = true;
                        setThreadTurnStatus("Codex is still processing. Queue will retry when it finishes.", false);
                        scheduleThreadPoll();
                    } else {
                        setThreadTurnStatus(friendlyThreadError(error, "Queued send failed."), true);
                    }
                    renderQueuedTurns();
                    rerenderCurrentThreadMessages();
                    updateThreadComposerState();
                });
            }
        });
    }

    private boolean isConflictError(Exception error) {
        String message = error == null ? "" : error.getMessage();
        return message != null && message.startsWith("HTTP 409");
    }

    private String friendlyThreadError(Exception error, String fallback) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return fallback;
        }

        int jsonStart = message.indexOf('{');
        if (jsonStart >= 0) {
            try {
                JSONObject object = new JSONObject(message.substring(jsonStart));
                String detail = object.optString("error", "").trim();
                String kind = object.optString("kind", "").trim();
                if (!detail.isEmpty()) {
                    if ("invalid-cwd".equals(kind) || detail.toLowerCase(Locale.US).contains("project folder")) {
                        return "Project folder problem: " + detail;
                    }
                    if (detail.toLowerCase(Locale.US).contains("another codex turn")) {
                        return "Codex is busy. Message preserved in the queue.";
                    }
                    return detail;
                }
            } catch (JSONException ignored) {
            }
        }

        String lower = message.toLowerCase(Locale.US);
        if (lower.contains("writable project folder") || lower.contains("project folder")) {
            return "Project folder problem: " + message;
        }
        if (lower.contains("workspacewrite") || lower.contains("readonly") || lower.contains("read-only")) {
            return "Permission problem: " + message;
        }
        return message;
    }

    private void updateThreadComposerState() {
        if (threadSendButton == null || threadPromptInput == null) {
            return;
        }
        boolean queueMode = currentThreadActive || isSendingThreadTurn;
        threadSendButton.setEnabled(true);
        threadSendButton.setText(queueMode ? "Queue" : "Send");
        threadPromptInput.setHint(currentThreadActive
                ? "Queue a message for this chat"
                : "Message Codex in this chat");
    }

    private void renderQueuedTurns() {
        if (queuedTurnListLayout == null) {
            return;
        }
        queuedTurnListLayout.removeAllViews();
        if (queuedThreadTurns.isEmpty()) {
            queuedTurnListLayout.setVisibility(View.GONE);
            return;
        }

        queuedTurnListLayout.setVisibility(View.VISIBLE);
        TextView title = text("Queued messages", 12, COLOR_INK, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(4));
        queuedTurnListLayout.addView(title, matchWrap());

        for (QueuedTurn turn : new ArrayList<>(queuedThreadTurns)) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(10), dp(8), dp(10), dp(8));
            item.setBackground(outlineDrawable(Color.rgb(252, 251, 248), COLOR_BORDER, dp(8)));

            TextView summary = text(queueSummary(turn), 12, COLOR_INK, Typeface.NORMAL);
            summary.setLineSpacing(dp(2), 1.0f);
            item.addView(summary, matchWrap());

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.END);
            row.setPadding(0, dp(6), 0, 0);

            Button editButton = toolbarButton("Edit", Color.WHITE, COLOR_PRIMARY);
            editButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
            editButton.setOnClickListener(view -> editQueuedTurn(turn));
            row.addView(editButton, new LinearLayout.LayoutParams(dp(58), dp(34)));

            row.addView(spacer(dp(6)));

            Button deleteButton = toolbarButton("Delete", Color.WHITE, COLOR_ACCENT);
            deleteButton.setBackground(outlineDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
            deleteButton.setOnClickListener(view -> deleteQueuedTurn(turn));
            row.addView(deleteButton, new LinearLayout.LayoutParams(dp(72), dp(34)));

            item.addView(row, matchWrap());
            queuedTurnListLayout.addView(item, matchWrap());
            queuedTurnListLayout.addView(spacer(dp(6)));
        }
    }

    private String queueSummary(QueuedTurn turn) {
        String prompt = turn.payload.optString("prompt", "").trim();
        if (prompt.isEmpty()) {
            prompt = "Image-only message";
        }
        if (prompt.length() > 100) {
            prompt = truncateMessageBody(prompt, 100).replace("\n\n...", "...");
        }
        JSONArray images = turn.payload.optJSONArray("images");
        int imageCount = images == null ? 0 : images.length();
        StringBuilder builder = new StringBuilder();
        builder.append(timeFormat.format(new Date(turn.createdAt))).append("\n").append(prompt);
        if (imageCount > 0) {
            builder.append("\n").append(imageCount).append(imageCount == 1 ? " image attached" : " images attached");
        }
        return builder.toString();
    }

    private void editQueuedTurn(QueuedTurn turn) {
        if (!queuedThreadTurns.remove(turn)) {
            return;
        }
        persistQueue();
        threadPromptInput.setText(turn.payload.optString("prompt", ""));
        threadPromptInput.setSelection(threadPromptInput.length());
        selectedImageUri = null;
        selectedImageName = "";
        selectedImageMimeType = "";
        editingQueuedImages = copyJsonArray(turn.payload.optJSONArray("images"));
        updateAttachmentPreview();
        renderQueuedTurns();
        rerenderCurrentThreadMessages();
        updateThreadComposerState();
        setThreadTurnStatus(editingQueuedImages != null && editingQueuedImages.length() > 0
                ? "Editing queued message. Attached image is preserved."
                : "Editing queued message.", false);
    }

    private void deleteQueuedTurn(QueuedTurn turn) {
        if (queuedThreadTurns.remove(turn)) {
            persistQueue();
            renderQueuedTurns();
            rerenderCurrentThreadMessages();
            updateThreadComposerState();
            setThreadTurnStatus(queuedThreadTurns.isEmpty()
                    ? "Queue cleared."
                    : "Queued message deleted.", false);
        }
    }

    private void clearThreadQueue() {
        queuedThreadTurns.clear();
        editingQueuedImages = null;
        clearSelectedImageState();
        persistQueue();
        renderQueuedTurns();
        rerenderCurrentThreadMessages();
        updateThreadComposerState();
    }

    private void ensureQueueLoadedForCurrentThread() {
        if (currentThreadId == null || currentThreadId.isEmpty()) {
            unloadThreadQueue();
            return;
        }
        if (currentThreadId.equals(queueThreadId)) {
            return;
        }
        loadQueueForThread(currentThreadId);
    }

    private void loadQueueForThread(String threadId) {
        queuedThreadTurns.clear();
        queueThreadId = threadId;
        File file = queueFileForThread(threadId);
        if (!file.exists()) {
            renderQueuedTurns();
            return;
        }

        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            JSONArray array = new JSONArray(output.toString(StandardCharsets.UTF_8.name()));
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                JSONObject payload = item.optJSONObject("payload");
                if (payload == null) {
                    continue;
                }
                queuedThreadTurns.add(new QueuedTurn(
                        payload,
                        item.optString("id", ""),
                        item.optLong("createdAt", 0L)));
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not load queue for thread " + threadId, error);
        }
        renderQueuedTurns();
    }

    private void persistQueue() {
        if (queueThreadId == null || queueThreadId.isEmpty()) {
            return;
        }
        try {
            File file = queueFileForThread(queueThreadId);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            JSONArray array = new JSONArray();
            for (QueuedTurn turn : queuedThreadTurns) {
                array.put(new JSONObject()
                        .put("id", turn.id)
                        .put("createdAt", turn.createdAt)
                        .put("payload", turn.payload));
            }
            try (FileOutputStream output = new FileOutputStream(file, false)) {
                output.write(array.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not persist queue", error);
        }
    }

    private void unloadThreadQueue() {
        queuedThreadTurns.clear();
        queueThreadId = null;
        editingQueuedImages = null;
        sendingThreadPayload = null;
        clearSelectedImageState();
        renderQueuedTurns();
        rerenderCurrentThreadMessages();
        updateThreadComposerState();
    }

    private File queueFileForThread(String threadId) {
        return new File(new File(getFilesDir(), "queues"), safeFileName(threadId) + ".json");
    }

    private String safeFileName(String value) {
        String safe = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (safe.isEmpty()) {
            return "thread";
        }
        return safe.length() > 120 ? safe.substring(0, 120) : safe;
    }

    private JSONArray copyJsonArray(JSONArray value) {
        if (value == null) {
            return null;
        }
        try {
            return new JSONArray(value.toString());
        } catch (JSONException ignored) {
            return null;
        }
    }

    private void scheduleThreadPoll() {
        if (mainHandler == null || currentThreadId == null || currentThreadId.isEmpty()) {
            return;
        }
        mainHandler.removeCallbacks(threadPollRunnable);
        long delayMs = fastPollRemaining > 0 ? 1500 : 5000;
        if (fastPollRemaining > 0) {
            fastPollRemaining--;
        }
        mainHandler.postDelayed(threadPollRunnable, delayMs);
    }

    private void stopThreadPoll() {
        if (mainHandler != null) {
            mainHandler.removeCallbacks(threadPollRunnable);
        }
        isPollingThread = false;
    }

    private void pollCurrentThread() {
        if (currentThreadId == null || currentThreadId.isEmpty()) {
            stopThreadPoll();
            return;
        }
        if (isPollingThread || isLoadingThreadPage) {
            scheduleThreadPoll();
            return;
        }

        String endpoint = normalizedEndpoint();
        String token = tokenInput.getText().toString().trim();
        String threadId = currentThreadId;
        if (endpoint.isEmpty()) {
            stopThreadPoll();
            return;
        }

        isPollingThread = true;
        networkExecutor.execute(() -> {
            try {
                String threadEndpoint = threadEndpointFor(endpoint, threadId, null, false);
                String body = getCatalog(threadEndpoint, token);
                JSONObject response = new JSONObject(body);
                mainHandler.post(() -> {
                    isPollingThread = false;
                    if (!threadId.equals(currentThreadId)) {
                        return;
                    }
                    renderThreadPage(endpoint, response, false, false);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    isPollingThread = false;
                    if (threadId.equals(currentThreadId)) {
                        setThreadTurnStatus(error.getMessage() == null ? "Could not refresh chat." : error.getMessage(), true);
                        scheduleThreadPoll();
                    }
                });
            }
        });
    }

    private JSONObject imagePayloadForUri(Uri uri, String name, String mimeType) throws IOException, JSONException {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IOException("Could not open selected image.");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MAX_ATTACHMENT_BYTES) {
                    throw new IOException("Image is larger than 8 MB.");
                }
                output.write(buffer, 0, read);
            }
            String safeName = name == null || name.trim().isEmpty() ? "phone-image" : name.trim();
            String safeMimeType = mimeType == null || mimeType.trim().isEmpty() ? "image/*" : mimeType.trim();
            return new JSONObject()
                    .put("name", safeName)
                    .put("mimeType", safeMimeType)
                    .put("size", output.size())
                    .put("base64", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
        }
    }

    private String postPayload(String endpoint, String token, String content) throws IOException, JSONException {
        URL url = new URL(endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        if (!token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }

        JSONObject payload = new JSONObject()
                .put("source", "android")
                .put("kind", looksLikeUrl(content) ? "url" : "text")
                .put("content", content)
                .put("sentAt", System.currentTimeMillis())
                .put("appVersion", "0.1.0");

        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);

        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }

        int status = connection.getResponseCode();
        String response = readResponse(connection, status);
        connection.disconnect();

        if (status >= 200 && status < 300) {
            return "Delivered to desktop (" + status + ").";
        }

        if (response.isEmpty()) {
            throw new IOException("Desktop endpoint replied with HTTP " + status + ".");
        }

        throw new IOException("HTTP " + status + ": " + response);
    }

    private boolean looksLikeUrl(String content) {
        return Patterns.WEB_URL.matcher(content).matches();
    }

    private String readResponse(HttpURLConnection connection, int status) {
        InputStream stream = status >= 400 ? connection.getErrorStream() : null;
        try {
            if (stream == null) {
                stream = connection.getInputStream();
            }
            if (stream == null) {
                return "";
            }

            try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[512];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (output.size() + read > MAX_RESPONSE_BYTES) {
                        return "Response was too large.";
                    }
                    output.write(buffer, 0, read);
                }
                return output.toString(StandardCharsets.UTF_8.name()).trim();
            }
        } catch (IOException ignored) {
            return "";
        }
    }

    private void addHistory(String content, String endpoint) {
        try {
            JSONArray history = new JSONArray(preferences.getString(PREF_HISTORY, "[]"));
            JSONObject item = new JSONObject()
                    .put("sentAt", System.currentTimeMillis())
                    .put("endpoint", endpoint)
                    .put("content", content);
            history.put(item);
            while (history.length() > 5) {
                history.remove(0);
            }
            preferences.edit().putString(PREF_HISTORY, history.toString()).apply();
        } catch (JSONException ignored) {
            preferences.edit().putString(PREF_HISTORY, "[]").apply();
        }

        updateHistoryView();
    }

    private void pickThreadImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_PICK_IMAGE);
        } catch (Exception error) {
            setThreadTurnStatus("No image picker available.", true);
        }
    }

    private void clearSelectedImage() {
        editingQueuedImages = null;
        clearSelectedImageState();
        setThreadTurnStatus("Image removed.", false);
    }

    private void clearSelectedImageState() {
        selectedImageUri = null;
        selectedImageName = "";
        selectedImageMimeType = "";
        updateAttachmentPreview();
    }

    private void updateAttachmentPreview() {
        if (attachmentPreviewLayout == null || attachmentStatusView == null) {
            return;
        }
        int preservedImageCount = editingQueuedImages == null ? 0 : editingQueuedImages.length();
        if (selectedImageUri == null && preservedImageCount == 0) {
            attachmentPreviewLayout.setVisibility(View.GONE);
            attachmentStatusView.setText("");
            return;
        }
        attachmentPreviewLayout.setVisibility(View.VISIBLE);
        if (selectedImageUri != null) {
            attachmentStatusView.setText("Attached image: " + (selectedImageName.isEmpty() ? "image" : selectedImageName));
        } else {
            attachmentStatusView.setText(preservedImageCount == 1
                    ? "Attached queued image preserved"
                    : preservedImageCount + " queued images preserved");
        }
    }

    private String displayNameForUri(Uri uri) {
        String result = "";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    result = cursor.getString(nameIndex);
                }
            }
        } catch (Exception ignored) {
        }
        if (result == null || result.trim().isEmpty()) {
            result = "phone-image";
        }
        return result.trim();
    }

    private void clearHistory() {
        preferences.edit().remove(PREF_HISTORY).apply();
        updateHistoryView();
        setStatus("History cleared.", false);
    }

    private void updateHistoryView() {
        try {
            JSONArray history = new JSONArray(preferences.getString(PREF_HISTORY, "[]"));
            if (history.length() == 0) {
                historyView.setText("No sends yet.");
                return;
            }

            StringBuilder builder = new StringBuilder();
            for (int index = history.length() - 1; index >= 0; index--) {
                JSONObject item = history.getJSONObject(index);
                Date sentAt = new Date(item.optLong("sentAt"));
                String content = item.optString("content");
                String preview = content.length() > 90 ? content.substring(0, 87) + "..." : content;
                builder
                        .append(timeFormat.format(sentAt))
                        .append("\n")
                        .append(preview)
                        .append("\n\n");
            }
            historyView.setText(builder.toString().trim());
        } catch (JSONException error) {
            historyView.setText("No sends yet.");
        }
    }

    private void setStatus(String message, boolean isError) {
        statusView.setText(message);
        statusView.setTextColor(isError ? COLOR_ACCENT : COLOR_MUTED);
    }

    private void setCatalogStatus(String message, boolean isError) {
        catalogStatusView.setText(message);
        catalogStatusView.setTextColor(isError ? COLOR_ACCENT : COLOR_MUTED);
    }

    private void setThreadTurnStatus(String message, boolean isError) {
        threadTurnStatusView.setText(message);
        threadTurnStatusView.setTextColor(isError ? COLOR_ACCENT : COLOR_MUTED);
    }

    private void showThreadResponseOverlay(String message) {
        if (threadResponseOverlay == null || threadResponseText == null) {
            return;
        }
        String preview = message.trim();
        if (preview.length() > 5000) {
            preview = preview.substring(0, 5000) + "\n...";
        }
        threadResponseText.setText(preview);
        threadResponseOverlay.setVisibility(View.VISIBLE);
        mainHandler.postDelayed(() -> {
            if (threadResponseOverlay != null && threadResponseOverlay.getVisibility() == View.VISIBLE) {
                threadResponseOverlay.setVisibility(View.GONE);
            }
        }, 12000);
    }

    private void hideThreadResponseOverlay() {
        if (threadResponseOverlay != null) {
            threadResponseOverlay.setVisibility(View.GONE);
        }
    }

    private String jsonString(JSONObject object, String key) {
        if (object == null || object.isNull(key)) {
            return "";
        }
        return object.optString(key, "");
    }

    private long jsonLong(JSONObject object, String key) {
        if (object == null || object.isNull(key)) {
            return 0L;
        }
        return object.optLong(key, 0L);
    }

    private String normalizedEndpoint() {
        String endpoint = endpointInput.getText().toString().trim();
        int httpIndex = endpoint.indexOf("http://");
        int httpsIndex = endpoint.indexOf("https://");
        int start = -1;
        if (httpIndex >= 0 && httpsIndex >= 0) {
            start = Math.min(httpIndex, httpsIndex);
        } else if (httpIndex >= 0) {
            start = httpIndex;
        } else if (httpsIndex >= 0) {
            start = httpsIndex;
        }
        if (start > 0) {
            endpoint = endpoint.substring(start).trim();
        }
        return endpoint;
    }
}
