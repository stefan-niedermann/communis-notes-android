package it.niedermann.owncloud.notes.persistence;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.nextcloud.android.sso.AccountImporter;
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppAccountNotFoundException;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import it.niedermann.android.sharedpreferences.SharedPreferenceIntLiveData;
import it.niedermann.owncloud.notes.R;
import it.niedermann.owncloud.notes.edit.EditNoteActivity;
import it.niedermann.owncloud.notes.persistence.dao.AccountDao;
import it.niedermann.owncloud.notes.persistence.dao.CategoryDao;
import it.niedermann.owncloud.notes.persistence.dao.NoteDao;
import it.niedermann.owncloud.notes.persistence.dao.WidgetNotesListDao;
import it.niedermann.owncloud.notes.persistence.dao.WidgetSingleNoteDao;
import it.niedermann.owncloud.notes.persistence.entity.Account;
import it.niedermann.owncloud.notes.persistence.entity.Category;
import it.niedermann.owncloud.notes.persistence.entity.Converters;
import it.niedermann.owncloud.notes.persistence.entity.Note;
import it.niedermann.owncloud.notes.persistence.entity.NoteWithCategory;
import it.niedermann.owncloud.notes.persistence.entity.NotesListWidgetData;
import it.niedermann.owncloud.notes.persistence.entity.SingleNoteWidgetData;
import it.niedermann.owncloud.notes.persistence.migration.Migration_10_11;
import it.niedermann.owncloud.notes.persistence.migration.Migration_11_12;
import it.niedermann.owncloud.notes.persistence.migration.Migration_12_13;
import it.niedermann.owncloud.notes.persistence.migration.Migration_13_14;
import it.niedermann.owncloud.notes.persistence.migration.Migration_14_15;
import it.niedermann.owncloud.notes.persistence.migration.Migration_15_16;
import it.niedermann.owncloud.notes.persistence.migration.Migration_16_17;
import it.niedermann.owncloud.notes.persistence.migration.Migration_17_18;
import it.niedermann.owncloud.notes.persistence.migration.Migration_18_19;
import it.niedermann.owncloud.notes.persistence.migration.Migration_19_20;
import it.niedermann.owncloud.notes.persistence.migration.Migration_9_10;
import it.niedermann.owncloud.notes.shared.model.ApiVersion;
import it.niedermann.owncloud.notes.shared.model.Capabilities;
import it.niedermann.owncloud.notes.shared.model.CategorySortingMethod;
import it.niedermann.owncloud.notes.shared.model.DBStatus;
import it.niedermann.owncloud.notes.shared.model.ENavigationCategoryType;
import it.niedermann.owncloud.notes.shared.model.ISyncCallback;
import it.niedermann.owncloud.notes.shared.model.NavigationCategory;
import it.niedermann.owncloud.notes.shared.util.NoteUtil;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.O;
import static androidx.lifecycle.Transformations.map;
import static it.niedermann.owncloud.notes.edit.EditNoteActivity.ACTION_SHORTCUT;
import static it.niedermann.owncloud.notes.shared.util.NoteUtil.generateNoteExcerpt;
import static it.niedermann.owncloud.notes.widget.notelist.NoteListWidget.updateNoteListWidgets;
import static it.niedermann.owncloud.notes.widget.singlenote.SingleNoteWidget.updateSingleNoteWidgets;

@Database(
        entities = {
                Account.class,
                Note.class,
                Category.class,
                SingleNoteWidgetData.class,
                NotesListWidgetData.class
        }, version = 20
)
@TypeConverters({Converters.class})
public abstract class NotesDatabase extends RoomDatabase {

    private static final String TAG = NotesDatabase.class.getSimpleName();
    private static final String NOTES_DB_NAME = "OWNCLOUD_NOTES";
    private static NotesDatabase instance;
    private static Context context;
    private static NoteServerSyncHelper serverSyncHelper;

    private static NotesDatabase create(final Context context) {
        return Room.databaseBuilder(
                context,
                NotesDatabase.class,
                NOTES_DB_NAME)
                .addMigrations(
                        new Migration_9_10(), // v2.0.0
                        new Migration_10_11(context),
                        new Migration_11_12(context),
                        new Migration_12_13(context),
                        new Migration_13_14(context, () -> instance.notifyWidgets()),
                        new Migration_14_15(),
                        new Migration_15_16(context, () -> instance.notifyWidgets()),
                        new Migration_16_17(),
                        new Migration_17_18(),
                        new Migration_18_19(context),
                        new Migration_19_20()
                )
                .fallbackToDestructiveMigrationOnDowngrade()
//                .fallbackToDestructiveMigration()
                .addCallback(new RoomDatabase.Callback() {
                    @Override
                    public void onCreate(@NonNull SupportSQLiteDatabase db) {
                        super.onCreate(db);
                        db.execSQL("CREATE TRIGGER TRG_CLEANUP_CATEGORIES_DEL AFTER DELETE ON Note BEGIN DELETE FROM Category WHERE Category.id NOT IN (SELECT Note.categoryId FROM Note); END;");
                        db.execSQL("CREATE TRIGGER TRG_CLEANUP_CATEGORIES_UPD AFTER UPDATE ON Note BEGIN DELETE FROM Category WHERE Category.id NOT IN (SELECT Note.categoryId FROM Note); END;");
                        Log.v(TAG, NotesDatabase.class.getSimpleName() + " created.");
                    }
                })
                .allowMainThreadQueries() // FIXME remove
                .build();
    }

    public abstract AccountDao getAccountDao();

    public abstract CategoryDao getCategoryDao();

    public abstract NoteDao getNoteDao();

    public abstract WidgetSingleNoteDao getWidgetSingleNoteDao();

    public abstract WidgetNotesListDao getWidgetNotesListDao();

    public static NotesDatabase getInstance(@NonNull Context context) {
        if (instance == null) {
            instance = create(context.getApplicationContext());
            NotesDatabase.context = context.getApplicationContext();
            NotesDatabase.serverSyncHelper = NoteServerSyncHelper.getInstance(instance);
        }
        return instance;
    }

    public NoteServerSyncHelper getNoteServerSyncHelper() {
        return NotesDatabase.serverSyncHelper;
    }

    /**
     * Creates a new Note in the Database and adds a Synchronization Flag.
     *
     * @param note Note
     */
    @NonNull
    @MainThread
    public LiveData<NoteWithCategory> addNoteAndSync(Account account, NoteWithCategory note) {
        NoteWithCategory entity = new NoteWithCategory(new Note(0, null, note.getModified(), note.getTitle(), note.getContent(), note.getFavorite(), note.getETag(), DBStatus.LOCAL_EDITED, account.getId(), generateNoteExcerpt(note.getContent(), note.getTitle()), 0), note.getCategory());
        final MutableLiveData<NoteWithCategory> ret = new MutableLiveData<>();
        new Thread(() -> ret.postValue(addNote(account.getId(), entity))).start();
        return map(ret, newNoteWithCategory -> {
            notifyWidgets();
            serverSyncHelper.scheduleSync(account, true);
            return newNoteWithCategory;
        });
    }

    /**
     * Inserts a note directly into the Database.
     * No Synchronisation will be triggered! Use addNoteAndSync()!
     *
     * @param note Note to be added. Remotely created Notes must be of type CloudNote and locally created Notes must be of Type {@link Note} (with {@link DBStatus#LOCAL_EDITED})!
     */
    @NonNull
    @WorkerThread
    NoteWithCategory addNote(long accountId, NoteWithCategory note) {
        Note entity = new Note();
        if (note.getId() != null) {
            if (note.getId() > 0) {
                entity.setId(note.getId());
            }
            entity.setStatus(note.getStatus());
            entity.setAccountId(note.getAccountId());
            entity.setExcerpt(note.getExcerpt());
        } else {
            entity.setStatus(DBStatus.VOID);
            entity.setAccountId(accountId);
            entity.setExcerpt(generateNoteExcerpt(note.getContent(), note.getTitle()));
        }
        if (note.getRemoteId() != null && note.getRemoteId() > 0) {
            entity.setRemoteId(note.getRemoteId());
        }
        entity.setTitle(note.getTitle());
        entity.setModified(note.getModified());
        entity.setContent(note.getContent());
        entity.setFavorite(note.getFavorite());
        entity.setCategoryId(getOrCreateCategoryIdByTitle(accountId, note.getCategory()));
        entity.setETag(note.getETag());
        return getNoteDao().getFullNoteWithCategory(accountId, getNoteDao().addNote(entity));
    }

    @AnyThread
    public LiveData<NoteWithCategory> moveNoteToAnotherAccount(Account account, NoteWithCategory note) {
        NoteWithCategory noteWithCategory = new NoteWithCategory(new Note(null, note.getModified(), note.getTitle(), getNoteDao().getContent(note.getId()), note.getFavorite(), null), note.getCategory());
        deleteNoteAndSync(account, note.getId());
        return addNoteAndSync(account, noteWithCategory);
    }

    @NonNull
    @WorkerThread
    public Map<Long, Long> getIdMap(long accountId) {
        validateAccountId(accountId);
        Map<Long, Long> result = new HashMap<>();
        for (Note note : getNoteDao().getRemoteIdAndId(accountId)) {
            result.put(note.getRemoteId(), note.getId());
        }
        return result;
    }

    @AnyThread
    public void toggleFavoriteAndSync(Account account, long noteId) {
        new Thread(() -> {
            getNoteDao().toggleFavorite(noteId);
            serverSyncHelper.scheduleSync(account, true);
        }).start();
    }

    /**
     * Set the category for a given note.
     * This method will search in the database to find out the category id in the db.
     * If there is no such category existing, this method will create it and search again.
     *
     * @param account    The single sign on account
     * @param noteId     The note which will be updated
     * @param category   The category title which should be used to find the category id.
     */
    @AnyThread
    public void setCategory(@NonNull Account account, long noteId, @NonNull String category) {
        new Thread(() -> {
            getNoteDao().updateStatus(noteId, DBStatus.LOCAL_EDITED);
            getNoteDao().updateCategory(noteId, getOrCreateCategoryIdByTitle(account.getId(), category));
            serverSyncHelper.scheduleSync(account, true);
        }).start();
    }

    /**
     * Updates a single Note with a new content.
     * The title is derived from the new content automatically, and modified date as well as DBStatus are updated, too -- if the content differs to the state in the database.
     *
     * @param oldNote    Note to be changed
     * @param newContent New content. If this is <code>null</code>, then <code>oldNote</code> is saved again (useful for undoing changes).
     * @param newTitle   New title. If this is <code>null</code>, then either the old title is reused (in case the note has been synced before) or a title is generated (in case it is a new note)
     * @param callback   When the synchronization is finished, this callback will be invoked (optional).
     * @return changed {@link Note} if differs from database, otherwise the old {@link Note}.
     */
    @WorkerThread
    public NoteWithCategory updateNoteAndSync(Account localAccount, @NonNull NoteWithCategory oldNote, @Nullable String newContent, @Nullable String newTitle, @Nullable ISyncCallback callback) {
        final NoteWithCategory newNote;
        if (newContent == null) {
            newNote = new NoteWithCategory(new Note(oldNote.getId(), oldNote.getRemoteId(), oldNote.getModified(), oldNote.getTitle(), oldNote.getContent(), oldNote.getFavorite(), oldNote.getETag(), DBStatus.LOCAL_EDITED, localAccount.getId(), oldNote.getExcerpt(), oldNote.getScrollY()), oldNote.getCategory());
        } else {
            final String title;
            if (newTitle != null) {
                title = newTitle;
            } else {
                if (oldNote.getRemoteId() == null || oldNote.getRemoteId() == 0 || localAccount.getPreferredApiVersion() == null || localAccount.getPreferredApiVersion().compareTo(new ApiVersion("1.0", 0, 0)) < 0) {
                    title = NoteUtil.generateNonEmptyNoteTitle(newContent, context);
                } else {
                    title = oldNote.getTitle();
                }
            }
            newNote = new NoteWithCategory(new Note(oldNote.getId(), oldNote.getRemoteId(), Calendar.getInstance(), title, newContent, oldNote.getFavorite(), oldNote.getETag(), DBStatus.LOCAL_EDITED, localAccount.getId(), generateNoteExcerpt(newContent, title), oldNote.getScrollY()), oldNote.getCategory());
        }
        newNote.getNote().setCategoryId(getOrCreateCategoryIdByTitle(newNote.getAccountId(), newNote.getCategory()));
        int rows = getNoteDao().updateNote(newNote.getNote());
        // if data was changed, set new status and schedule sync (with callback); otherwise invoke callback directly.
        if (rows > 0) {
            notifyWidgets();
            if (callback != null) {
                serverSyncHelper.addCallbackPush(localAccount, callback);
            }
            serverSyncHelper.scheduleSync(localAccount, true);
            return newNote;
        } else {
            if (callback != null) {
                callback.onFinish();
            }
            return oldNote;
        }
    }

    /**
     * Marks a Note in the Database as Deleted. In the next Synchronization it will be deleted
     * from the Server.
     *
     * @param id long - ID of the Note that should be deleted
     */
    @AnyThread
    public void deleteNoteAndSync(Account account, long id) {
        new Thread(() -> {
            getNoteDao().updateStatus(id, DBStatus.LOCAL_DELETED);
            notifyWidgets();
            serverSyncHelper.scheduleSync(account, true);

            if (SDK_INT >= O) {
                ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);
                if (shortcutManager != null) {
                    shortcutManager.getPinnedShortcuts().forEach((shortcut) -> {
                        String shortcutId = id + "";
                        if (shortcut.getId().equals(shortcutId)) {
                            Log.v(TAG, "Removing shortcut for " + shortcutId);
                            shortcutManager.disableShortcuts(Collections.singletonList(shortcutId), context.getResources().getString(R.string.note_has_been_deleted));
                        }
                    });
                } else {
                    Log.e(TAG, ShortcutManager.class.getSimpleName() + "is null.");
                }
            }
        }).start();
    }

    /**
     * Notify about changed notes.
     */
    @AnyThread
    protected void notifyWidgets() {
        new Thread(() -> {
            updateSingleNoteWidgets(context);
            updateNoteListWidgets(context);
        }).start();
    }

    @AnyThread
    void updateDynamicShortcuts(long accountId) {
        new Thread(() -> {
            if (SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {
                ShortcutManager shortcutManager = context.getApplicationContext().getSystemService(ShortcutManager.class);
                if (shortcutManager != null) {
                    if (!shortcutManager.isRateLimitingActive()) {
                        List<ShortcutInfo> newShortcuts = new ArrayList<>();

                        for (Note note : getNoteDao().getRecentNotes(accountId)) {
                            if (!TextUtils.isEmpty(note.getTitle())) {
                                Intent intent = new Intent(context.getApplicationContext(), EditNoteActivity.class);
                                intent.putExtra(EditNoteActivity.PARAM_NOTE_ID, note.getId());
                                intent.setAction(ACTION_SHORTCUT);

                                newShortcuts.add(new ShortcutInfo.Builder(context.getApplicationContext(), note.getId() + "")
                                        .setShortLabel(note.getTitle() + "")
                                        .setIcon(Icon.createWithResource(context.getApplicationContext(), note.getFavorite() ? R.drawable.ic_star_yellow_24dp : R.drawable.ic_star_grey_ccc_24dp))
                                        .setIntent(intent)
                                        .build());
                            } else {
                                // Prevent crash https://github.com/stefan-niedermann/nextcloud-notes/issues/613
                                Log.e(TAG, "shortLabel cannot be empty " + note);
                            }
                        }
                        Log.d(TAG, "Update dynamic shortcuts");
                        shortcutManager.removeAllDynamicShortcuts();
                        shortcutManager.addDynamicShortcuts(newShortcuts);
                    }
                }
            }
        }).start();
    }

    @AnyThread
    public LiveData<Account> addAccount(@NonNull String url, @NonNull String username, @NonNull String accountName, @NonNull Capabilities capabilities) {
        return getAccountDao().getAccountLiveData(getAccountDao().insert(new Account(url, username, accountName, capabilities)));
    }

    /**
     * @param apiVersion has to be a JSON array as a string <code>["0.2", "1.0", ...]</code>
     * @return whether or not the given {@link ApiVersion} has been written to the database
     * @throws IllegalArgumentException if the apiVersion does not match the expected format
     */
    public boolean updateApiVersion(long accountId, @Nullable String apiVersion) throws IllegalArgumentException {
        validateAccountId(accountId);
        if (apiVersion != null) {
            try {
                JSONArray apiVersions = new JSONArray(apiVersion);
                for (int i = 0; i < apiVersions.length(); i++) {
                    ApiVersion.of(apiVersions.getString(i));
                }
                if (apiVersions.length() > 0) {
                    final int updatedRows = getAccountDao().updateApiVersion(accountId, apiVersion);
                    if (updatedRows == 1) {
                        Log.i(TAG, "Updated apiVersion to \"" + apiVersion + "\" for accountId = " + accountId);
                    } else {
                        Log.e(TAG, "Updated " + updatedRows + " but expected only 1 for accountId = " + accountId + " and apiVersion = \"" + apiVersion + "\"");
                    }
                    return true;
                } else {
                    Log.i(TAG, "Given API version is a valid JSON array but does not contain any valid API versions. Do not update database.");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("API version does contain a non-valid version: " + apiVersion);
            } catch (JSONException e) {
                throw new IllegalArgumentException("API version must contain be a JSON array: " + apiVersion);
            }
        } else {
            Log.v(TAG, "Given API version is null. Do not update database");
        }
        return false;
    }

    /**
     * @param localAccount the {@link Account} that should be deleted
     * @throws IllegalArgumentException if no account has been deleted by the given accountId
     */
    @AnyThread
    public LiveData<Void> deleteAccount(@NonNull Account localAccount) throws IllegalArgumentException {
        validateAccountId(localAccount.getId());
        MutableLiveData<Void> ret = new MutableLiveData<>();
        new Thread(() -> {
            int deletedAccounts = getAccountDao().deleteAccount(localAccount);
            if (deletedAccounts < 1) {
                Log.e(TAG, "AccountId '" + localAccount.getId() + "' did not delete any account");
                throw new IllegalArgumentException("The given accountId does not delete any row");
            } else if (deletedAccounts > 1) {
                Log.e(TAG, "AccountId '" + localAccount.getId() + "' deleted unexpectedly '" + deletedAccounts + "' accounts");
            }

            try {
                SSOClient.invalidateAPICache(AccountImporter.getSingleSignOnAccount(context, localAccount.getAccountName()));
            } catch (NextcloudFilesAppAccountNotFoundException e) {
                e.printStackTrace();
                SSOClient.invalidateAPICache();
            }

            final int deletedNotes = getNoteDao().deleteByAccountId(localAccount.getId());
            Log.v(TAG, "Deleted " + deletedNotes + " notes from account " + localAccount.getId());
            ret.postValue(null);
        }).start();
        return ret;
    }

    /**
     * Get the category if with the given category title
     * The method does not support fuzzy search.
     * Because the category title in database is unique, there will not at most one result.
     * If there is no such category, database will create it if create flag is set.
     * Otherwise this method will return -1 as default value.
     *
     * @param accountId     The user {@link Account} Id
     * @param categoryTitle The category title which will be search in the db
     * @return -1 if there is no such category else the corresponding id
     */
    @NonNull
    @WorkerThread
    private Long getOrCreateCategoryIdByTitle(long accountId, @NonNull String categoryTitle) {
        validateAccountId(accountId);
        Long categoryId = getCategoryDao().getCategoryIdByTitle(accountId, categoryTitle);
        if (categoryId != null && categoryId > 0) {
            return categoryId;
        } else {
            Category entity = new Category();
            entity.setAccountId(accountId);
            entity.setTitle(categoryTitle);
            return getCategoryDao().addCategory(entity);
        }
    }

    private static void validateAccountId(long accountId) {
        if (accountId < 1) {
            throw new IllegalArgumentException("accountId must be greater than 0");
        }
    }

    /**
     * Modifies the sorting method for one category, the category can be normal category or
     * one of "All notes", "Favorite", and "Uncategorized".
     * If category is one of these three, sorting method will be modified in android.content.SharedPreference.
     * The user can determine use which sorting method to show the notes for a category.
     * When the user changes the sorting method, this method should be called.
     *
     * @param accountId        The user accountID
     * @param selectedCategory The category to be modified
     * @param sortingMethod    The sorting method in {@link CategorySortingMethod} enum format
     */
    @AnyThread
    public void modifyCategoryOrder(long accountId, @NonNull NavigationCategory selectedCategory, @NonNull CategorySortingMethod sortingMethod) {
        validateAccountId(accountId);

        new Thread(() -> {
            final Context ctx = context.getApplicationContext();
            final SharedPreferences.Editor sp = PreferenceManager.getDefaultSharedPreferences(ctx).edit();
            int orderIndex = sortingMethod.getCSMID();

            switch (selectedCategory.getType()) {
                case FAVORITES: {
                    sp.putInt(ctx.getString(R.string.action_sorting_method) + ' ' + ctx.getString(R.string.label_favorites), orderIndex);
                    break;
                }
                case UNCATEGORIZED: {
                    sp.putInt(ctx.getString(R.string.action_sorting_method) + ' ' + ctx.getString(R.string.action_uncategorized), orderIndex);
                    break;
                }
                case RECENT: {
                    sp.putInt(ctx.getString(R.string.action_sorting_method) + ' ' + ctx.getString(R.string.label_all_notes), orderIndex);
                    break;
                }
                case DEFAULT_CATEGORY:
                default: {
                    final Category category = selectedCategory.getCategory();
                    if (category != null) {
                        getCategoryDao().modifyCategoryOrder(accountId, category.getId(), sortingMethod);
                    } else {
                        throw new IllegalStateException("Tried to modify category order for " + ENavigationCategoryType.DEFAULT_CATEGORY + "but category is null.");
                    }
                    break;
                }
            }
            sp.apply();
        }).start();
    }

    /**
     * Gets the sorting method of a {@link NavigationCategory}, the category can be normal
     * {@link Category} or one of {@link ENavigationCategoryType}.
     * If the category no normal {@link Category}, sorting method will be got from
     * {@link SharedPreferences}.
     * <p>
     * The sorting method of the category can be used to decide to use which sorting method to show
     * the notes for each categories.
     *
     * @param selectedCategory The category
     * @return The sorting method in CategorySortingMethod enum format
     */
    @NonNull
    @MainThread
    public LiveData<CategorySortingMethod> getCategoryOrder(@NonNull NavigationCategory selectedCategory) {
        final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        String prefKey;

        switch (selectedCategory.getType()) {
            // TODO make this account specific
            case RECENT: {
                prefKey = context.getString(R.string.action_sorting_method) + ' ' + context.getString(R.string.label_all_notes);
                break;
            }
            case FAVORITES: {
                prefKey = context.getString(R.string.action_sorting_method) + ' ' + context.getString(R.string.label_favorites);
                break;
            }
            case UNCATEGORIZED: {
                prefKey = context.getString(R.string.action_sorting_method) + ' ' + context.getString(R.string.action_uncategorized);
                break;
            }
            case DEFAULT_CATEGORY:
            default: {
                final Category category = selectedCategory.getCategory();
                if (category != null) {
                    return getCategoryDao().getCategoryOrder(category.getId());
                } else {
                    Log.e(TAG, "Cannot read " + CategorySortingMethod.class.getSimpleName() + " for " + ENavigationCategoryType.DEFAULT_CATEGORY + ".");
                    return new MutableLiveData<>(CategorySortingMethod.SORT_MODIFIED_DESC);
                }
            }
        }

        return map(new SharedPreferenceIntLiveData(sp, prefKey, CategorySortingMethod.SORT_MODIFIED_DESC.getCSMID()), CategorySortingMethod::getCSM);
    }

    /**
     * Updates a single Note with data from the server, (if it was not modified locally).
     * Thereby, an optimistic concurrency control is realized in order to prevent conflicts arising due to parallel changes from the UI and synchronization.
     * This is used by the synchronization task, hence no Synchronization will be triggered. Use updateNoteAndSync() instead!
     *
     * @param id                        local ID of Note
     * @param remoteNote                Note from the server.
     * @param forceUnchangedDBNoteState is not null, then the local note is updated only if it was not modified meanwhile
     */
    void updateNote(long accountId, long id, @NonNull NoteWithCategory remoteNote, @Nullable NoteWithCategory forceUnchangedDBNoteState) {
        validateAccountId(accountId);
        // First, update the remote ID, since this field cannot be changed in parallel, but have to be updated always.
        getNoteDao().updateRemoteId(id, remoteNote.getRemoteId());

        // The other columns have to be updated in dependency of forceUnchangedDBNoteState,
        // since the Synchronization-Task must not overwrite locales changes!
        if (forceUnchangedDBNoteState != null) {
            getNoteDao().updateIfModifiedLocallyDuringSync(id, remoteNote.getModified().getTimeInMillis(), remoteNote.getTitle(), remoteNote.getFavorite(), remoteNote.getCategory(), remoteNote.getETag(), remoteNote.getContent());
        } else {
            getNoteDao().updateIfNotModifiedLocallyAndRemoteColumnHasChanged(id, remoteNote.getModified().getTimeInMillis(), remoteNote.getTitle(), remoteNote.getFavorite(), remoteNote.getCategory(), remoteNote.getETag(), remoteNote.getContent());
        }
        Log.d(TAG, "updateNote: " + remoteNote + " || forceUnchangedDBNoteState: " + forceUnchangedDBNoteState + "");
    }

    public Context getContext() {
        return NotesDatabase.context;
    }
}
