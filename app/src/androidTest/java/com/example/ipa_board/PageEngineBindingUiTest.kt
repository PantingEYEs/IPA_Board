package com.example.ipa_board

import android.app.AlertDialog
import android.content.DialogInterface
import android.widget.Button
import android.widget.ListView
import androidx.viewpager2.widget.ViewPager2
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PageEngineBindingUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun fixture(body: (String) -> Unit) {
        LayoutFileManager.initDefaultLayout(context)
        val groups = PageGroupTestState(context)
        val file = LayoutFileManager.createLayout(context,"page-engine-ui-${UUID.randomUUID()}",SettingsConstants.DEFAULT_LAYOUT)
        try {
            PageGroupManager.setPages(context,PageGroupManager.active(context).id,listOf(file))
            PageGroupManager.selectPage(context,file)
            body(file)
        } finally { LayoutFileManager.deleteLayout(context,file); groups.close() }
    }
    private fun open(scenario: ActivityScenario<KeyboardPageActivity>) {
        scenario.onActivity { it.findViewById<ViewPager2>(R.id.view_pager).setCurrentItem(1,false) }
        instrumentation.waitForIdleSync()
        onView(withId(R.id.btn_choose_engines)).perform(scrollTo(),click())
    }

    @Test fun dialogSharesNamesSavesFullSelectionAndCancelPreservesThePage() = fixture { file ->
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            open(scenario)
            val names = EngineCatalog.configurable().map { it.name }
            onView(isAssignableFrom(ListView::class.java)).inRoot(isDialog()).check { view, _ ->
                val list = view as ListView
                assertEquals(names,(0 until list.count).map { list.adapter.getItem(it).toString() })
            }
            // The list can scroll; use data-backed positions rather than matching only visible labels.
            for (index in names.indices) {
                androidx.test.espresso.Espresso.onData(org.hamcrest.Matchers.anything()).inRoot(isDialog())
                    .inAdapterView(isAssignableFrom(ListView::class.java))
                    .atPosition(index).perform(click())
            }
            onView(withText("Cancel")).inRoot(isDialog()).perform(click())
            assertNull(LayoutFileManager.loadLayout(context,file)!!.engineBinding)
            open(scenario)
            val target = EngineCatalog.configurable().indexOfFirst { it.feature == EngineFeature.SEMANTIC }
            androidx.test.espresso.Espresso.onData(org.hamcrest.Matchers.anything()).inRoot(isDialog())
                .atPosition(target).perform(click())
            onView(withText("Save")).inRoot(isDialog()).perform(click())
            val saved = LayoutFileManager.loadLayout(context,file)!!.engineBinding!!
            assertEquals(EngineFeature.entries.map { it.key }.toSet(),saved.states.keys)
            assertEquals(!EngineSettings.enabled(context,EngineFeature.SEMANTIC),saved.states[EngineFeature.SEMANTIC.key])
            scenario.recreate()
            open(scenario)
            onView(isAssignableFrom(ListView::class.java)).inRoot(isDialog()).check { view, _ ->
                val list = view as ListView
                EngineCatalog.configurable().forEachIndexed { index, info ->
                    assertEquals(saved.states[info.feature!!.key],list.isItemChecked(index))
                }
            }
            onView(withText("Cancel")).inRoot(isDialog()).perform(click())
            assertEquals(saved,LayoutFileManager.loadLayout(context,file)!!.engineBinding)
        }
    }

    @Test fun emptySelectionIsBoundAndClearBindingReturnsToUnbound() = fixture { file ->
        val allOff=PageEngineBinding(EngineFeature.entries.associate { it.key to false })
        LayoutFileManager.saveLayout(context,file,LayoutFileManager.loadLayout(context,file)!!.copy(engineBinding=allOff))
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            open(scenario)
            onView(withText("Save")).inRoot(isDialog()).perform(click())
            assertEquals(allOff,LayoutFileManager.loadLayout(context,file)!!.engineBinding)
            open(scenario)
            onView(withText("Clear Binding")).inRoot(isDialog()).perform(click())
            assertNull(LayoutFileManager.loadLayout(context,file)!!.engineBinding)
            open(scenario)
            onView(withText("Cancel")).inRoot(isDialog()).perform(click())
        }
    }

    @Test fun bindingSurvivesRenameImportAndSharingAPageAcrossGroups() = fixture { original ->
        val binding=PageEngineBinding(EngineFeature.entries.associate { it.key to false } + ("future_feature" to true))
        var renamed: String?=null
        var imported: String?=null
        try {
            LayoutFileManager.saveLayout(context,original,LayoutFileManager.loadLayout(context,original)!!.copy(engineBinding=binding))
            renamed=LayoutFileManager.renameLayout(context,original,"page-binding-renamed-${UUID.randomUUID()}")
            val second=PageGroupManager.create(context,"Another group")
            PageGroupManager.addPages(context,second,listOf(renamed))
            PageGroupManager.selectPage(context,renamed)
            assertEquals(binding,LayoutFileManager.loadLayout(context,renamed)!!.engineBinding)
            val exported=LayoutFileManager.loadLayout(context,renamed)!!.toJson()
            imported=LayoutFileManager.importLayout(context,exported.byteInputStream(),"page-binding-imported-${UUID.randomUUID()}")
            assertEquals(binding,LayoutFileManager.loadLayout(context,imported)!!.engineBinding)
            LayoutFileManager.saveLayout(context,imported,LayoutFileManager.loadLayout(context,imported)!!.copy(engineBinding=null))
            assertEquals(binding,LayoutFileManager.loadLayout(context,renamed)!!.engineBinding)
        } finally {
            imported?.let { LayoutFileManager.deleteLayout(context,it) }
            renamed?.let { LayoutFileManager.deleteLayout(context,it) }
        }
    }
}
