package com.isaac.stock.predict;

import com.isaac.stock.model.RecurrentNets;
import com.isaac.stock.representation.PriceCategory;
import com.isaac.stock.representation.StockDataSetIterator;
import com.isaac.stock.utils.PlotUtil;
import javafx.util.Pair;
import lombok.SneakyThrows;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.util.ModelSerializer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.io.ClassPathResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Created by zhanghao on 26/7/17.
 * Modified by zhanghao on 28/9/17.
 * @author ZHANG HAO
 */
public class StockPricePredictionNew {

    private static final Logger log = LoggerFactory.getLogger(StockPricePredictionNew.class);

    private static int exampleLength = 80; // time series length, assume 22 working days per month

    public static void main (String[] args) throws IOException {
        String file = new ClassPathResource("BTC_USDT_MIN5_2023-07-17T134746_2023-08-16T134746.csv").getFile().getAbsolutePath();
        String symbol = "BTC";      // stock name
        int batchSize = 64;         // mini-batch size
        double splitRatio = 0.9;    // 90% for training, 10% for testing
        PriceCategory category = PriceCategory.CLOSE;

        tradingAndStoreModel(file, symbol, category, batchSize, splitRatio);
        loadModelAndShowPredictionGraph(file, symbol, category, batchSize, splitRatio);
    }

    private static File getFileModel(String symbol, PriceCategory category) {
        return new File("src/main/resources/StockPriceLSTM_" + symbol + "_".concat(String.valueOf(category)).concat(".zip"));
    }

    @SneakyThrows
    private static void loadModelAndShowPredictionGraph(String fileData, String symbol, PriceCategory category, int batchSize, double splitRatio) {
        File fileModel = getFileModel(symbol, category);

        log.info("Create dataSet iterator...");
        StockDataSetIterator iterator = new StockDataSetIterator(fileData, symbol, batchSize, exampleLength, splitRatio, category);

        log.info("Load model...");
        MultiLayerNetwork net = ModelSerializer.restoreMultiLayerNetwork(fileModel);
        log.info("Load test dataset...");
        List<Pair<INDArray, INDArray>> test = iterator.getTestDataSet();

        log.info("Testing...");
        if (category.equals(PriceCategory.ALL)) {
            INDArray max = Nd4j.create(iterator.getMaxArray());
            INDArray min = Nd4j.create(iterator.getMinArray());
            predictAllCategories(net, test, max, min);
        } else {
            double max = iterator.getMaxNum(category);
            double min = iterator.getMinNum(category);
            predictPriceOneAhead(net, test, max, min, category);
        }
        log.info("Done...");
    }

    @SneakyThrows
    private static void tradingAndStoreModel(String file, String symbol, PriceCategory category, int batchSize, double splitRatio) {
        int epochs = 100; // training epochs

        log.info("Create dataSet iterator...");
        StockDataSetIterator iterator = new StockDataSetIterator(file, symbol, batchSize, exampleLength, splitRatio, category);

        log.info("Build lstm networks...");
        MultiLayerNetwork net = RecurrentNets.buildLstmNetworks(iterator.inputColumns(), iterator.totalOutcomes());

        log.info("Training...");
        for (int i = 0; i < epochs; i++) {
            log.info("iteration: " + i);
            while (iterator.hasNext()) net.fit(iterator.next()); // fit model using mini-batch data
            iterator.reset(); // reset iterator
            net.rnnClearPreviousState(); // clear previous state
        }

        log.info("Saving model...");
        File locationToSave = getFileModel(symbol, category);
        // saveUpdater: i.e., the state for Momentum, RMSProp, Adagrad etc. Save this to train your network more in the future
        ModelSerializer.writeModel(net, locationToSave, true);
    }

    /** Predict one feature of a stock one-day ahead */
    private static void predictPriceOneAhead (MultiLayerNetwork net, List<Pair<INDArray, INDArray>> testData, double max, double min, PriceCategory category) {
        double[] predicts = new double[testData.size()];
        double[] actuals = new double[testData.size()];
        for (int i = 0; i < testData.size(); i++) {
            predicts[i] = net.rnnTimeStep(testData.get(i).getKey()).getDouble(exampleLength - 1) * (max - min) + min;
            actuals[i] = testData.get(i).getValue().getDouble(0);
        }
        log.info("Print out Predictions and Actual Values...");
        log.info("Predict,Actual");
        for (int i = 0; i < predicts.length; i++) log.info(predicts[i] + "," + actuals[i]);
        log.info("Plot...");
        PlotUtil.plot(predicts, actuals, String.valueOf(category));
    }

    /** Predict all the features (open, close, low, high prices and volume) of a stock one-day ahead */
    private static void predictAllCategories (MultiLayerNetwork net, List<Pair<INDArray, INDArray>> testData, INDArray max, INDArray min) {
        INDArray[] predicts = new INDArray[testData.size()];
        INDArray[] actuals = new INDArray[testData.size()];
        for (int i = 0; i < testData.size(); i++) {
            predicts[i] = net.rnnTimeStep(testData.get(i).getKey()).getRow(exampleLength - 1).mul(max.sub(min)).add(min);
            actuals[i] = testData.get(i).getValue();
        }
        log.info("Print out Predictions and Actual Values...");
        log.info("Predict\tActual");
        for (int i = 0; i < predicts.length; i++) log.info(predicts[i] + "\t" + actuals[i]);
        log.info("Plot...");
        for (int n = 0; n < 5; n++) {
            double[] pred = new double[predicts.length];
            double[] actu = new double[actuals.length];
            for (int i = 0; i < predicts.length; i++) {
                pred[i] = predicts[i].getDouble(n);
                actu[i] = actuals[i].getDouble(n);
            }
            String name;
            switch (n) {
                case 0: name = "Stock OPEN Price"; break;
                case 1: name = "Stock CLOSE Price"; break;
                case 2: name = "Stock LOW Price"; break;
                case 3: name = "Stock HIGH Price"; break;
                case 4: name = "Stock VOLUME Amount"; break;
                default: throw new NoSuchElementException();
            }
            PlotUtil.plot(pred, actu, name);
        }
    }

}
