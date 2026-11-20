package com.javierrb35.bibliotecaia.study;

import android.app.Activity;
import android.content.Context;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * Android API 34 selection-menu regression: no replacement of platform actions
 * when the study commands are injected. This tests the real Android Menu API,
 * not a manual Samsung long-press gesture.
 */
@RunWith(AndroidJUnit4.class)
public class DefinitionSelectionDeviceTest {
    private static DefinitionUi.Delegate noExternalRequests() {
        return new DefinitionUi.Delegate() {
            @Override public void openExternal(String url) { fail("Unexpected external URL"); }
            @Override public void explainWithGemini(String text) { fail("Unexpected AI request"); }
            @Override public void showError(String message) { fail("Unexpected error: " + message); }
        };
    }

    @Test public void preservesPlatformItemsAndForwardsTheirClickToAndroid() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
            TextView reader = new TextView(ctx);
            reader.setText("Santiago Casares informó al consejo.");
            DefinitionUi.attach(new Activity(), reader, false, noExternalRequests());
            ActionMode.Callback callback = reader.getCustomSelectionActionModeCallback();
            assertNotNull(callback);

            Menu menu = new PopupMenu(ctx, reader).getMenu();
            MenuItem copy = menu.add(Menu.NONE, android.R.id.copy, 0, "Copiar");
            MenuItem paste = menu.add(Menu.NONE, android.R.id.paste, 1, "Pegar");
            MenuItem selectAll = menu.add(Menu.NONE, android.R.id.selectAll, 2, "Seleccionar todo");
            MenuItem share = menu.add(Menu.NONE, android.R.id.shareText, 3, "Compartir");
            assertTrue(callback.onCreateActionMode(null, menu));
            assertSame(copy, menu.findItem(android.R.id.copy));
            assertSame(paste, menu.findItem(android.R.id.paste));
            assertSame(selectAll, menu.findItem(android.R.id.selectAll));
            assertSame(share, menu.findItem(android.R.id.shareText));
            assertFalse(callback.onActionItemClicked(null, copy));
            assertFalse(callback.onActionItemClicked(null, paste));
            assertFalse(callback.onActionItemClicked(null, selectAll));
            assertFalse(callback.onActionItemClicked(null, share));
            assertTrue(callback.onPrepareActionMode(null, menu));
            assertNotNull(menu.findItem(android.R.id.copy));
        });
    }

    @Test public void readonlyTextGetsCopySelectAllAndShareWithoutFakePaste() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
            TextView reader = new TextView(ctx);
            reader.setText("BibliotecaAI");
            DefinitionUi.attach(new Activity(), reader, false, noExternalRequests());
            Menu menu = new PopupMenu(ctx, reader).getMenu();
            assertTrue(reader.getCustomSelectionActionModeCallback().onCreateActionMode(null, menu));
            assertNotNull(menu.findItem(android.R.id.copy));
            assertNotNull(menu.findItem(android.R.id.selectAll));
            assertNotNull(menu.findItem(android.R.id.shareText));
            assertNull(menu.findItem(android.R.id.paste));
        });
    }

    @Test public void readiumSelectionRetainsNativeActionsAlongsideStudyCommands() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
            TextView anchor = new TextView(ctx);
            Menu menu = new PopupMenu(ctx, anchor).getMenu();
            MenuItem nativeCopy = menu.add(Menu.NONE, android.R.id.copy, 0, "Copiar");
            MenuItem nativeSelect = menu.add(Menu.NONE, android.R.id.selectAll, 1, "Seleccionar todo");
            DefinitionUi.populateSelectionMenu(menu);
            assertSame(nativeCopy, menu.findItem(android.R.id.copy));
            assertSame(nativeSelect, menu.findItem(android.R.id.selectAll));
            assertTrue(DefinitionUi.isStudyAction(menu.findItem(0x5C120).getItemId()));
        });
    }
}
