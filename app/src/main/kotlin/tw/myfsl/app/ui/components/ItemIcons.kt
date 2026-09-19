package tw.myfsl.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Checkroom
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.House
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalCarWash
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Redeem
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import tw.myfsl.app.core.model.FlowType

/** 項目圖示：依項目名稱裡的關鍵字挑，找不到用種類的預設圖示。順序有意義，先比對較具體的字。 */
object ItemIcons {
    private val rules: List<Pair<List<String>, ImageVector>> = listOf(
        listOf("洗車") to Icons.Rounded.LocalCarWash,
        listOf("油") to Icons.Rounded.LocalGasStation,
        listOf("保養", "維修", "修車") to Icons.Rounded.Build,
        listOf("停車", "牌照", "燃料", "車") to Icons.Rounded.DirectionsCar,
        listOf("交通", "捷運", "公車", "高鐵", "通勤") to Icons.Rounded.DirectionsBus,
        listOf("卡費", "信用卡", "卡循") to Icons.Rounded.CreditCard,
        listOf("房貸", "房租", "租金") to Icons.Rounded.House,
        listOf("貸款", "貸", "利息") to Icons.Rounded.AccountBalance,
        listOf("保險", "保單", "保費") to Icons.Rounded.HealthAndSafety,
        listOf("醫", "藥", "健檢") to Icons.Rounded.LocalHospital,
        listOf("學", "補習", "教育", "書", "才藝", "課") to Icons.Rounded.School,
        listOf("托", "保母", "尿布", "奶粉", "小孩") to Icons.Rounded.ChildCare,
        listOf("孝親", "父母", "家人") to Icons.Rounded.FamilyRestroom,
        listOf("家用", "家") to Icons.Rounded.Home,
        listOf("頭髮", "美髮", "剪髮") to Icons.Rounded.ContentCut,
        listOf("旅", "出國") to Icons.Rounded.Luggage,
        listOf("電話", "手機", "網路", "電信") to Icons.Rounded.PhoneAndroid,
        listOf("水費", "水") to Icons.Rounded.WaterDrop,
        listOf("電費", "瓦斯", "電") to Icons.Rounded.Bolt,
        listOf("訂閱", "串流") to Icons.Rounded.Subscriptions,
        listOf("娛樂", "電影") to Icons.Rounded.Movie,
        listOf("寵物", "貓", "狗") to Icons.Rounded.Pets,
        listOf("衣", "服飾") to Icons.Rounded.Checkroom,
        listOf("咖啡", "飲料") to Icons.Rounded.LocalCafe,
        listOf("禮", "紅包") to Icons.Rounded.CardGiftcard,
        listOf("稅") to Icons.AutoMirrored.Rounded.ReceiptLong,
        listOf("投資", "股") to Icons.AutoMirrored.Rounded.TrendingUp,
        listOf("儲蓄", "存", "定存") to Icons.Rounded.Savings,
        listOf("獎金", "補助", "紅利") to Icons.Rounded.Redeem,
        listOf("薪", "收入") to Icons.Rounded.Payments,
        listOf("生活", "餐", "食", "吃") to Icons.Rounded.Restaurant,
        listOf("購物", "日用", "雜支") to Icons.Rounded.ShoppingCart,
    )

    fun of(name: String, type: FlowType): ImageVector =
        rules.firstOrNull { (words, _) -> words.any { it in name } }?.second ?: when (type) {
            FlowType.INCOME -> Icons.Rounded.Payments
            FlowType.TRANSFER -> Icons.Rounded.SwapHoriz
            FlowType.EXPENSE -> Icons.Rounded.Category
        }
}
