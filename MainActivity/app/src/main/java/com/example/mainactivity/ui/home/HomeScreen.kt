package com.example.mainactivity.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mainactivity.R   // ★ R 임포트 필수!

@Composable
fun HomeScreen(onGetStarted: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(     // ★ 이름 있는 파라미터로 모호성 방지
                    colors = listOf(
                        Color(0xFF9333EA), // primary
                        Color(0xFF2563EB)  // secondary
                    )
                )
            )
            .padding(32.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 1400.dp)
                .align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Section
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                // Logo
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_volume),
                            contentDescription = "Logo",
                            modifier = Modifier.size(28.dp),
                            tint = Color.White
                        )
                    }
                    Text(
                        text = "AI Voice Detector",
                        fontSize = 24.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Main Heading
                Text(
                    text = "AI 복제 음성 및\n딥페이크로부터\n보호하세요",
                    fontSize = 60.sp,
                    lineHeight = 72.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                // Description
                Text(
                    text = "우리는 오디오가 실제인지 아닌지\nAI가 생성한 것인지 식별할 수 있는 AI 도구를\n제공합니다.",
                    fontSize = 20.sp,
                    lineHeight = 32.sp,
                    color = Color.White.copy(alpha = 0.9f)
                )

                // CTA Button
                Button(
                    onClick = onGetStarted,
                    modifier = Modifier
                        .height(64.dp)
                        .shadow(8.dp, RoundedCornerShape(16.dp)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "지금 시작하기",
                        fontSize = 18.sp,
                        color = Color(0xFF9333EA),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // Right Section - Phone Mockup with Sparkles
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                PhoneMockupWithSparkles()
            }
        }
    }
}

@Composable
fun PhoneMockupWithSparkles() {
    Box(contentAlignment = Alignment.Center) {
        // Sparkles
        AnimatedSparkle(
            modifier = Modifier.offset(x = (-100).dp, y = (-80).dp),
            delay = 0,
            color = Color(0xFFFDE047) // yellow-300
        )
        AnimatedSparkle(
            modifier = Modifier.offset(x = 80.dp, y = (-40).dp),
            delay = 300,
            color = Color(0xFFD8B4FE) // purple-300
        )
        AnimatedSparkle(
            modifier = Modifier.offset(x = 60.dp, y = 120.dp),
            delay = 700,
            color = Color(0xFF67E8F9) // cyan-300
        )

        // Phone Mockup
        PhoneMockup()
    }
}

@Composable
fun AnimatedSparkle(
    modifier: Modifier = Modifier,
    delay: Int = 0,
    color: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sparkle")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, delayMillis = delay),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Icon(
        painter = painterResource(id = R.drawable.ic_sparkle),
        contentDescription = "Sparkle",
        modifier = modifier.size(48.dp),
        tint = color.copy(alpha = alpha)
    )
}

@Composable
fun PhoneMockup() {
    Box(
        modifier = Modifier
            .width(256.dp)
            .height(500.dp)
            .shadow(20.dp, RoundedCornerShape(48.dp))
            .clip(RoundedCornerShape(48.dp))
            .background(
                brush = Brush.linearGradient(     // ★ 이름 있는 파라미터
                    colors = listOf(
                        Color(0xFF7C3AED), // purple-700
                        Color(0xFF2563EB)  // blue-700
                    )
                )
            )
            .padding(12.dp)
    ) {
        // Notch
        Box(
            modifier = Modifier
                .width(128.dp)
                .height(24.dp)
                .align(Alignment.TopCenter)
                .offset(y = (-12).dp)
                .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                .background(Color.Black)
        )

        // Screen
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(40.dp))
                .background(Color.White)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Volume Icon Circle
                Box(
                    modifier = Modifier
                        .size(128.dp)
                        .clip(RoundedCornerShape(64.dp))
                        .background(
                            brush = Brush.linearGradient(   // ★ 이름 있는 파라미터
                                colors = listOf(
                                    Color(0xFFE9D5FF), // purple-200
                                    Color(0xFFBFDBFE)  // blue-200
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_volume),
                        contentDescription = "Volume",
                        modifier = Modifier.size(64.dp),
                        tint = Color(0xFF9333EA)
                    )
                }

                // Placeholder Lines
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.75f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFE5E7EB))
                            .align(Alignment.CenterHorizontally)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFE5E7EB))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFE5E7EB))
                            .align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}
