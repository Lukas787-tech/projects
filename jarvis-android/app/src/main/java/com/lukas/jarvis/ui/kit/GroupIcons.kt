package com.lukas.jarvis.ui.kit

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import com.lukas.jarvis.llm.ToolGroup

/** The icon each family of abilities wears on chips, cards and the Skills list. */
fun ToolGroup.icon(): ImageVector = when (this) {
    ToolGroup.Memory -> Icons.Rounded.Psychology
    ToolGroup.Money -> Icons.Rounded.Savings
    ToolGroup.Tasks -> Icons.Rounded.TaskAlt
    ToolGroup.Thinking -> Icons.Rounded.Calculate
    ToolGroup.Web -> Icons.Rounded.Language
    ToolGroup.Weather -> Icons.Rounded.WbSunny
    ToolGroup.Places -> Icons.Rounded.Place
    ToolGroup.Calendar -> Icons.Rounded.Event
    ToolGroup.People -> Icons.Rounded.Person
    ToolGroup.Phone -> Icons.Rounded.PhoneAndroid
    ToolGroup.Messages -> Icons.AutoMirrored.Rounded.Chat
    ToolGroup.Media -> Icons.Rounded.MusicNote
    ToolGroup.Screen -> Icons.Rounded.Visibility
    ToolGroup.Vision -> Icons.Rounded.PhotoCamera
    ToolGroup.Automation -> Icons.Rounded.AutoAwesome
    ToolGroup.News -> Icons.Rounded.Newspaper
    ToolGroup.Language -> Icons.Rounded.Translate
    ToolGroup.Create -> Icons.Rounded.Brush
    ToolGroup.Markets -> Icons.Rounded.ShowChart
    ToolGroup.Knowledge -> Icons.AutoMirrored.Rounded.MenuBook
    ToolGroup.Fun -> Icons.Rounded.Casino
    ToolGroup.Home -> Icons.Rounded.Home
}
