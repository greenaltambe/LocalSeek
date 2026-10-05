plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("clip_model")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}
