package alex.com.livecurrencyconverter.activities.components

import alex.com.livecurrencyconverter.activities.components.api.CurrencyAPI
import alex.com.livecurrencyconverter.activities.components.api.CurrencyAPIClient
import alex.com.livecurrencyconverter.other.Constants
import dagger.Module
import dagger.Provides
import retrofit2.Retrofit

/**
 * Created by Alex Doub on 11/13/2019.
 */

@Module
class CurrencyConverterModule {
    @Provides
    internal fun provideAPIClient(retrofit: Retrofit): CurrencyAPIClient {
        val currencyAPI = retrofit.create(CurrencyAPI::class.java)
        return CurrencyAPIClient(Constants.API_KEY, currencyAPI)
    }
}