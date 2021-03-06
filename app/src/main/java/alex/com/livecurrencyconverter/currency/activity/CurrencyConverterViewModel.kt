package alex.com.livecurrencyconverter.currency.activity

import alex.com.livecurrencyconverter.currency.api.CurrencyAPIClient
import alex.com.livecurrencyconverter.currency.repository.currency.CurrencyRepository
import alex.com.livecurrencyconverter.currency.repository.quote.QuoteEntity
import alex.com.livecurrencyconverter.currency.repository.quote.QuoteRepository
import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Created by Alex Doub on 11/13/2019.
 */

class CurrencyConverterViewModel(
    private val currencyAPIClient: CurrencyAPIClient,
    private val currencyRepository: CurrencyRepository,
    private val quoteRepository: QuoteRepository
) : ViewModel(), CoroutineScope by MainScope() {

    companion object {
        private const val DEFAULT_CURRENCY = "USD"    // API sets USD as the default currency
        private const val DEFAULT_AMOUNT = "1.00"
        private val DATA_STALE_DURATION = TimeUnit.MILLISECONDS.convert(30, TimeUnit.MINUTES)
    }

    // Read-Only Observables
    private val _currenciesObservable = MutableLiveData<List<String>>()
    val currenciesObservable: LiveData<List<String>>
        get() = _currenciesObservable
    private val _adjustedQuotesObservable = MutableLiveData<List<QuoteEntity>>()
    val adjustedQuotesObservable: LiveData<List<QuoteEntity>>
        get() = _adjustedQuotesObservable
    private val _isLoadingObservable = MutableLiveData<Boolean>()
    val isLoadingObservable: LiveData<Boolean>
        get() = _isLoadingObservable
    private val _isEmptyObservable = MutableLiveData<Boolean>()
    val isEmptyObservable: LiveData<Boolean>
        get() = _isEmptyObservable

    // 2-way Observables
    val amountObservable = MutableLiveData<String>().apply { value = DEFAULT_AMOUNT }

    // Events
    val showErrorEvent = MutableLiveData<String>()
    val showSnackbarEvent = MutableLiveData<String>()

    // Private data
    private var sourceCurrency: String = DEFAULT_CURRENCY
    private var destinationCurrency: String? = null
    private val isLoadingCurrencies = MutableLiveData<Boolean>().apply { value = false }
    private val isLoadingQuotes = MutableLiveData<Boolean>().apply { value = false }
    private var quoteEntities: List<QuoteEntity>? = null

    init {
        observeCurrencyRepo()
        observeQuoteRepo()
        observeAmountChanges()
        observeIsLoading()
        refreshData()
    }

    fun refreshData() {
        refreshCurrencies()
        refreshQuotes()
        refreshIsLoading()
    }

    private fun observeAmountChanges() {
        amountObservable.observeForever { amount ->
            if (amount?.toDoubleOrNull() == null) {
                showSnackbarEvent.postValue("Amount is not a number")
            }
            createAdjustedQuotes()
        }
    }

    private fun observeCurrencyRepo() {
        viewModelScope.launch {
            currencyRepository.getCurrencies().collect { entities ->
                val strings = entities.map { it.currency }
                _currenciesObservable.postValue(strings)
            }
        }
    }

    private fun refreshCurrencies() {
        val dataIsStale = currencyRepository.getLastSavedTime() + DATA_STALE_DURATION < System.currentTimeMillis()
        if (dataIsStale) {
            fetchCurrencies()
        }
    }

    private fun observeQuoteRepo() {
        viewModelScope.launch {
            quoteRepository.getQuotes().collect { quotes ->
                quoteEntities = quotes
                createAdjustedQuotes()
            }
        }
    }

    private fun refreshQuotes() {
        val dataIsStale = quoteRepository.getLastSavedTime() + DATA_STALE_DURATION < System.currentTimeMillis()
        if (dataIsStale) {
            fetchQuotes()
        }
    }

    private fun observeIsLoading() {
        isLoadingCurrencies.observeForever {
            refreshIsLoading()
        }
        isLoadingQuotes.observeForever {
            refreshIsLoading()
        }
    }

    private fun refreshIsLoading() {
        _isLoadingObservable.postValue(isLoadingQuotes.value!! || isLoadingCurrencies.value!!)
    }

    fun setSourceCurrency(currency: String) {
        sourceCurrency = currency
        createAdjustedQuotes()
    }

    fun setDestinationCurrency(currency: String?) {
        destinationCurrency = currency
        createAdjustedQuotes()
    }

    fun clearData() {
        viewModelScope.launch {
            currencyRepository.deleteCurrencies()
            quoteRepository.deleteQuotes()
            showSnackbarEvent.value = "DB & Prefs cleared. Pull to refresh to fetch data from server"
        }
    }

    private fun fetchCurrencies() {
        isLoadingCurrencies.postValue(true)
        showSnackbarEvent.value = "Fetching currencies from server... "

        // Kick off request
        viewModelScope.async {
            val response = currencyAPIClient.getCurrencies()
            when {
                response.error != null -> throw IOException("Error fetching currencies: ${response.error.info}")
                response.currencies == null -> throw IOException("Error fetching currencies: Network response body malformed")
                response.currencies.isEmpty() -> throw IOException("Error fetching currencies: No data returned")
                else -> {
                    // Save to repo
                    currencyRepository.insertCurrencies(response.currencies)
                }
            }
        }.invokeOnCompletion { throwable ->
            isLoadingCurrencies.postValue(false)
            throwable?.let {
                showErrorEvent.postValue(throwable.localizedMessage)
            }
        }
    }

    private fun fetchQuotes() {
        isLoadingQuotes.postValue(true)
        showSnackbarEvent.value = "Fetching quotes from server... "

        // Kick off request
        viewModelScope.async {
            val response = currencyAPIClient.getQuotes()
            when {
                response.error != null -> throw IOException("Error fetching quotes: ${response.error.info}")
                response.quotes == null -> throw IOException("Error fetching quotes: Network response body malformed")
                else -> {
                    // Save to repo
                    quoteRepository.insertQuotes(response.quotes)
                }
            }
        }.invokeOnCompletion { throwable ->
            isLoadingQuotes.postValue(false)
            throwable?.let {
                showErrorEvent.postValue(throwable.localizedMessage)
            }
        }
    }

    /**
     * Transform baseQuotes into an adjusted quote list based
     * on selected source, destination and amount.
     * */
    private fun createAdjustedQuotes() {

        // Assert repo is loaded
        val quotes = quoteEntities
        if (quotes == null || quotes.isEmpty()) {
            println("Quotes DB empty")
            setAdjustedQuotes(emptyList())
            return
        }

        // Find source conversion rate.
        var sourceConversionRate = 1.0
        if (sourceCurrency != DEFAULT_CURRENCY) {
            sourceConversionRate = quotes.find { it.currency == DEFAULT_CURRENCY + sourceCurrency }!!.value
        }

        // Update quotes according to selected source currency
        val amount = amountObservable.value?.toDoubleOrNull() ?: 0.0
        val adjustedQuotes = quotes.map {
            val newValue = amount * it.value / sourceConversionRate
            if (sourceCurrency == DEFAULT_CURRENCY) {
                QuoteEntity(it.currency, newValue)
            } else {
                val newCurrency = it.currency.replaceFirst(DEFAULT_CURRENCY, sourceCurrency)
                QuoteEntity(newCurrency, newValue)
            }
        }

        // Lastly, optionally filter to destination currency
        val filteredQuotes: List<QuoteEntity> = destinationCurrency?.let { destinationCurrency ->
            adjustedQuotes.filter { quote ->
                quote.currency.endsWith(destinationCurrency)
            }
        } ?: adjustedQuotes

        setAdjustedQuotes(filteredQuotes)
    }

    private fun setAdjustedQuotes(adjustedQuotes: List<QuoteEntity>) {
        isLoadingQuotes.postValue(false)
        _isEmptyObservable.postValue(adjustedQuotes.isEmpty())
        _adjustedQuotesObservable.postValue(adjustedQuotes)
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(
        private val currencyAPIClient: CurrencyAPIClient,
        private val currencyRepository: CurrencyRepository,
        private val quoteRepository: QuoteRepository
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel?> create(modelClass: Class<T>): T {
            return CurrencyConverterViewModel(
                currencyAPIClient = currencyAPIClient,
                currencyRepository = currencyRepository,
                quoteRepository = quoteRepository
            ) as T
        }
    }
}