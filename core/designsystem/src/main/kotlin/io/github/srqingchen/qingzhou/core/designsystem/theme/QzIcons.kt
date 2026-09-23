package io.github.srqingchen.qingzhou.core.designsystem.theme

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 品牌自绘图标（避免引入 icons-extended 的 ~30MB 体积）。
 * 均为 24dp 视口的填充路径。
 */
object QzIcons {

    /** 发现：雷达 —— 圆环 + 四分之一扫描扇形 + 中心点。 */
    val Radar: ImageVector by lazy {
        materialIcon(name = "Radar") {
            materialPath(pathFillType = PathFillType.EvenOdd) {
                // 外环（奇偶填充成环带）
                moveTo(12.0f, 3.0f)
                arcTo(9.0f, 9.0f, 0.0f, true, false, 12.01f, 3.0f)
                close()
                moveTo(12.0f, 5.2f)
                arcTo(6.8f, 6.8f, 0.0f, true, true, 11.99f, 5.2f)
                close()
            }
            materialPath {
                // 扫描扇形
                moveTo(12.0f, 12.0f)
                lineTo(12.0f, 5.6f)
                arcTo(6.4f, 6.4f, 0.0f, false, true, 18.4f, 12.0f)
                close()
            }
            materialPath {
                // 中心点
                moveTo(12.0f, 10.3f)
                arcTo(1.7f, 1.7f, 0.0f, true, false, 12.01f, 10.3f)
                close()
            }
        }
    }

    /** 传输：双向上/下箭头。 */
    val Transfer: ImageVector by lazy {
        materialIcon(name = "Transfer") {
            materialPath {
                // 上箭头
                moveTo(7.4f, 4.0f)
                lineTo(11.4f, 8.6f)
                horizontalLineTo(8.9f)
                verticalLineTo(20.0f)
                horizontalLineTo(5.9f)
                verticalLineTo(8.6f)
                horizontalLineTo(3.4f)
                close()
            }
            materialPath {
                // 下箭头
                moveTo(16.6f, 20.0f)
                lineTo(12.6f, 15.4f)
                horizontalLineTo(15.1f)
                verticalLineTo(4.0f)
                horizontalLineTo(18.1f)
                verticalLineTo(15.4f)
                horizontalLineTo(20.6f)
                close()
            }
        }
    }

    /** 收件箱：托盘 + 落入箭头。 */
    val Inbox: ImageVector by lazy {
        materialIcon(name = "Inbox") {
            materialPath {
                // 落入箭头
                moveTo(11.1f, 2.5f)
                horizontalLineTo(12.9f)
                verticalLineTo(7.0f)
                lineTo(14.6f, 5.3f)
                lineTo(15.9f, 6.6f)
                lineTo(12.0f, 10.5f)
                lineTo(8.1f, 6.6f)
                lineTo(9.4f, 5.3f)
                lineTo(11.1f, 7.0f)
                close()
            }
            materialPath {
                // 托盘
                moveTo(4.6f, 12.0f)
                horizontalLineTo(8.2f)
                curveToRelative(0.6f, 1.2f, 1.7f, 2.0f, 3.1f, 2.0f)
                horizontalLineTo(12.7f)
                curveToRelative(1.4f, 0.0f, 2.5f, -0.8f, 3.1f, -2.0f)
                horizontalLineTo(19.4f)
                verticalLineTo(17.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                horizontalLineTo(6.6f)
                curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                close()
            }
        }
    }

    /** 设置：滑杆组。 */
    val Sliders: ImageVector by lazy {
        materialIcon(name = "Sliders") {
            materialPath {
                moveTo(3.0f, 5.0f)
                horizontalLineTo(21.0f)
                verticalLineTo(6.9f)
                horizontalLineTo(3.0f)
                close()
                moveTo(3.0f, 11.0f)
                horizontalLineTo(21.0f)
                verticalLineTo(12.9f)
                horizontalLineTo(3.0f)
                close()
                moveTo(3.0f, 17.0f)
                horizontalLineTo(21.0f)
                verticalLineTo(18.9f)
                horizontalLineTo(3.0f)
                close()
            }
            materialPath {
                moveTo(15.0f, 6.0f)
                arcTo(2.3f, 2.3f, 0.0f, true, false, 15.01f, 6.0f)
                close()
                moveTo(8.0f, 12.0f)
                arcTo(2.3f, 2.3f, 0.0f, true, false, 8.01f, 12.0f)
                close()
                moveTo(17.0f, 18.0f)
                arcTo(2.3f, 2.3f, 0.0f, true, false, 17.01f, 18.0f)
                close()
            }
        }
    }
}
