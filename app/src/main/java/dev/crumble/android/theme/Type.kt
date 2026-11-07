package dev.crumble.android.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.unit.sp

private val base = Typography()

// Material's default tracking reads wider than the system apps.
val Typography = base.copy(
  titleMedium = base.titleMedium.copy(letterSpacing = 0.sp),
  titleSmall = base.titleSmall.copy(letterSpacing = 0.sp),
  bodyLarge = base.bodyLarge.copy(letterSpacing = 0.sp),
  bodyMedium = base.bodyMedium.copy(letterSpacing = 0.sp),
  bodySmall = base.bodySmall.copy(letterSpacing = 0.1.sp),
  labelLarge = base.labelLarge.copy(letterSpacing = 0.sp),
  labelMedium = base.labelMedium.copy(letterSpacing = 0.1.sp),
)
