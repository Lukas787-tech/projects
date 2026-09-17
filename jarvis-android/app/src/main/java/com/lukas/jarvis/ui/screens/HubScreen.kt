package com.lukas.jarvis.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.components.SegmentedTabs

/**
 * The three list screens under one roof.
 *
 * Memory, trackers and tasks are all "what Jarvis knows about my life", and they
 * are read one after another rather than one instead of another, so a segment is
 * a truer control for them than three separate destinations.
 *
 * The sections arrive as composables rather than as twenty hoisted parameters:
 * this screen's job is which one is showing, not what any of them needs.
 */
@Composable
fun HubScreen(
    memoryCount: Int,
    trackerCount: Int,
    openTaskCount: Int,
    memory: @Composable () -> Unit,
    trackers: @Composable () -> Unit,
    tasks: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    // Saveable, so a rotation does not throw you back to the first section.
    var selected by rememberSaveable { mutableStateOf(0) }

    val badges = remember(memoryCount, trackerCount, openTaskCount) {
        listOf(
            memoryCount.takeIf { it > 0 }?.toString(),
            trackerCount.takeIf { it > 0 }?.toString(),
            openTaskCount.takeIf { it > 0 }?.toString()
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        SegmentedTabs(
            options = listOf("Memory", "Trackers", "Tasks"),
            selectedIndex = selected,
            onSelect = { selected = it },
            badges = badges,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)
        )
        Spacer(Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxSize()) {
            when (selected) {
                1 -> trackers()
                2 -> tasks()
                else -> memory()
            }
        }
    }
}
