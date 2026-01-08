package app.aaps.plugins.source.libre.di

import app.aaps.plugins.source.libre.service.LibreService
import app.aaps.plugins.source.libre.ui.LibreFragment
import dagger.Module
import dagger.android.ContributesAndroidInjector

@Module
@Suppress("unused")
abstract class LibreModule {

    @ContributesAndroidInjector
    abstract fun contributesLibreFragment(): LibreFragment

    @ContributesAndroidInjector
    abstract fun contributesLibreService(): LibreService
}
