package com.lukas.jarvis.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * Which segment is showing is not kept here. It is an element like any other, so
 * the assistant saying "show your tasks" and a finger on the segment move the
 * same state, and the two can never end up disagreeing about what is in front of
 * the user.
 *
 * The sections arrive as composables rather than as twenty hoisted parameters:
 * this screen's job is which one is showing, not what any of them needs.
 */
@Composable
fun HubScreen(
    selected: Int,
    onSelect: (Int) -> Unit,
    memoryCount: Int,
    trackerCount: Int,
    openTaskCount: Int,
    memory: @Composable () -> Unit,
    trackers: @Composable () -> Unit,
    tasks: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    openListCount: Int = 0,
    lists: @Composable () -> Unit = {}
) {
    val badges = remember(memoryCount, trackerCount, openTaskCount, openListCount) {
        listOf(
            memoryCount.takeIf { it > 0 }?.toString(),
            trackerCount.takeIf { it > 0 }?.toString(),
            openTaskCount.takeIf { it > 0 }?.toString(),
            openListCount.takeIf { it > 0 }?.toString()
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        SegmentedTabs(
            options = listOf("Memory", "Trackers", "Tasks", "Lists"),
            selectedIndex = selected,
            onSelect = onSelect,
            badges = badges,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)
        )
        Spacer(Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxSize()) {
            when (selected) {
                1 -> trackers()
                2 -> tasks()
                3 -> lists()
                else -> memory()
            }
        }
    }
}
