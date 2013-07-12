package edu.stanford.nlp.ie.crf;

import edu.stanford.nlp.math.ArrayMath;
import edu.stanford.nlp.optimization.AbstractStochasticCachingDiffUpdateFunction;
import edu.stanford.nlp.optimization.HasFeatureGrouping;
import edu.stanford.nlp.util.ArrayUtils;
import edu.stanford.nlp.util.concurrent.*;
import edu.stanford.nlp.util.Index;
import edu.stanford.nlp.util.Timing;
import edu.stanford.nlp.util.Pair;
import edu.stanford.nlp.util.Quadruple;

import java.util.*;

/**
 * @author Jenny Finkel
 *         Mengqiu Wang
 */

public class CRFLogConditionalObjectiveFunction extends AbstractStochasticCachingDiffUpdateFunction implements HasCliquePotentialFunction, HasFeatureGrouping {

  public static final int NO_PRIOR = 0;
  public static final int QUADRATIC_PRIOR = 1;
  /* Use a Huber robust regression penalty (L1 except very near 0) not L2 */
  public static final int HUBER_PRIOR = 2;
  public static final int QUARTIC_PRIOR = 3;
  public static final int DROPOUT_PRIOR = 4;

  // public static final boolean DEBUG2 = true;
  public static final boolean DEBUG2 = false;
  public static final boolean DEBUG3 = false;
  public static final boolean TIMED = false;
  // public static final boolean TIMED = true;
  public static final boolean CONDENSE = true;
  // public static final boolean CONDENSE = false;

  private final int prior;
  private final double delta;
  private final double dropoutScale;
  private final int multiThreadGrad;
  private final double sigma;
  private final double epsilon = 0.1; // You can't actually set this at present
  /** label indices - for all possible label sequences - for each feature */
  private final List<Index<CRFLabel>> labelIndices;
  private final Index<String> classIndex;  // didn't have <String> before. Added since that's what is assumed everywhere.
  private final double[][] Ehat; // empirical counts of all the features [feature][class]
  private final int window;
  private final int numClasses;
  private final int[] map;
  private final int[][][][] data;  // data[docIndex][tokenIndex][][]
  private final double[][][][] featureVal;  // featureVal[docIndex][tokenIndex][][]
  private final int[][] labels;    // labels[docIndex][tokenIndex]
  private final int domainDimension;
  private double[][] eHat4Update, e4Update;

  private int[][] weightIndices;
  private double[][] weightSquare;

  private final String backgroundSymbol;
  private final boolean dropoutApprox;

  public static Index<String> featureIndex;

  // public static boolean VERBOSE = true;
  public static boolean VERBOSE = false;
  private static final double smallConst = 1e-6;
  private static final double largeConst = 5;

  private int[][] featureGrouping = null;

  private final int[][][][] totalData;  // data[docIndex][tokenIndex][][]
  private int unsupDropoutStartIndex;
  private final double unsupDropoutScale;

  private List<List<Set<Integer>>> dataFeatureHash; 
  private List<Map<Integer, List<Integer>>> condensedMap;
  private int[][] dataFeatureHashByDoc; 
  private Index<CRFLabel> edgeLabelIndex;
  private int edgeLabelIndexSize;
  private Index<CRFLabel> nodeLabelIndex;
  private int nodeLabelIndexSize;
  private int[][] edgeLabels; 
  private Map<Integer, List<Integer>> currPrevLabelsMap;
  private Map<Integer, List<Integer>> currNextLabelsMap;

  private Random rand = new Random(2147483647L);

  @Override
  public double[] initial() {
    double[] initial = new double[domainDimension()];
    for (int i = 0; i < initial.length; i++) {
      initial[i] = rand.nextDouble() + smallConst;
      // initial[i] = generator.nextDouble() * largeConst;
      // initial[i] = -1+2*(i);
      // initial[i] = (i == 0 ? 1 : 0);
    }
    return initial;
  }

  public static int getPriorType(String priorTypeStr) {
    if (priorTypeStr == null) return QUADRATIC_PRIOR;  // default
    if ("QUADRATIC".equalsIgnoreCase(priorTypeStr)) {
      return QUADRATIC_PRIOR;
    } else if ("HUBER".equalsIgnoreCase(priorTypeStr)) {
      return HUBER_PRIOR;
    } else if ("QUARTIC".equalsIgnoreCase(priorTypeStr)) {
      return QUARTIC_PRIOR;
    } else if ("DROPOUT".equalsIgnoreCase(priorTypeStr)) {
      return DROPOUT_PRIOR;
    } else if ("NONE".equalsIgnoreCase(priorTypeStr)) {
      return NO_PRIOR;
    } else if (priorTypeStr.equalsIgnoreCase("lasso") ||
               priorTypeStr.equalsIgnoreCase("ridge") ||
               priorTypeStr.equalsIgnoreCase("ae-lasso") ||
               priorTypeStr.equalsIgnoreCase("sg-lasso") ||
               priorTypeStr.equalsIgnoreCase("g-lasso") ) {
      return NO_PRIOR;
    } else {
      throw new IllegalArgumentException("Unknown prior type: " + priorTypeStr);
    }
  }

  CRFLogConditionalObjectiveFunction(int[][][][] data, int[][] labels, int window, Index<String> classIndex, List<Index<CRFLabel>> labelIndices, int[] map, String backgroundSymbol) {
    this(data, labels, window, classIndex, labelIndices, map, "QUADRATIC", backgroundSymbol);
  }

  CRFLogConditionalObjectiveFunction(int[][][][] data, int[][] labels, int window, Index<String> classIndex, List<Index<CRFLabel>> labelIndices, int[] map, String priorType, String backgroundSymbol) {
    this(data, labels, window, classIndex, labelIndices, map, priorType, backgroundSymbol, 1.0, null, 0.0, 1.0, 1, false, 0.0, null);
  }

  CRFLogConditionalObjectiveFunction(int[][][][] data, int[][] labels, int window, Index<String> classIndex, List<Index<CRFLabel>> labelIndices, int[] map, String backgroundSymbol, double sigma, double[][][][] featureVal) {
    this(data, labels, window, classIndex, labelIndices, map, "QUADRATIC", backgroundSymbol, sigma, featureVal, 0.0, 1.0, 1, false, 0.0, null);
  }

  CRFLogConditionalObjectiveFunction(int[][][][] data, int[][] labels, int window, Index<String> classIndex, List<Index<CRFLabel>> labelIndices, int[] map, String priorType, String backgroundSymbol, double sigma, double[][][][] featureVal, double delta, double dropoutScale, int multiThreadGrad, boolean dropoutApprox, double unsupDropoutScale, int[][][][] unsupDropoutData) {
    this.window = window;
    this.classIndex = classIndex;
    this.numClasses = classIndex.size();
    this.labelIndices = labelIndices;
    this.map = map;
    this.data = data;
    this.featureVal = featureVal;
    this.labels = labels;
    this.prior = getPriorType(priorType);
    this.backgroundSymbol = backgroundSymbol;
    this.sigma = sigma;
    this.delta = delta;
    this.dropoutScale = dropoutScale;
    this.dropoutApprox = dropoutApprox;
    this.multiThreadGrad = multiThreadGrad;
    Ehat = empty2D();
    empiricalCounts(Ehat);
    int myDomainDimension = 0;
    for (int dim : map) {
      myDomainDimension += labelIndices.get(dim).size();
    }
    this.unsupDropoutStartIndex = data.length;
    this.unsupDropoutScale = unsupDropoutScale;
    if (unsupDropoutData != null) {
      this.totalData = new int[data.length + unsupDropoutData.length][][][];
      for (int i=0; i<data.length; i++) {
        this.totalData[i] = data[i];
      }
      for (int i=0; i<unsupDropoutData.length; i++) {
        this.totalData[i+unsupDropoutStartIndex] = unsupDropoutData[i];
      }
    } else {
      this.totalData = data;
    }
    domainDimension = myDomainDimension;
    initEdgeLabels();
    initializeDataFeatureHash();
  }

  private void initEdgeLabels() {
    if (labelIndices.size() < 2)
      return;
    edgeLabelIndex = labelIndices.get(1);
    edgeLabelIndexSize = edgeLabelIndex.size();
    nodeLabelIndex = labelIndices.get(0);
    nodeLabelIndexSize = nodeLabelIndex.size();
    currPrevLabelsMap = new HashMap<Integer, List<Integer>>();
    currNextLabelsMap = new HashMap<Integer, List<Integer>>();
    edgeLabels = new int[edgeLabelIndexSize][];
    for (int k=0; k < edgeLabelIndexSize; k++) {
      int[] labelPair = edgeLabelIndex.get(k).getLabel();
      edgeLabels[k] = labelPair;
      int curr = labelPair[1];
      int prev = labelPair[0];
      if (!currPrevLabelsMap.containsKey(curr))
        currPrevLabelsMap.put(curr, new ArrayList<Integer>(numClasses));
      currPrevLabelsMap.get(curr).add(prev);
      if (!currNextLabelsMap.containsKey(prev))
        currNextLabelsMap.put(prev, new ArrayList<Integer>(numClasses));
      currNextLabelsMap.get(prev).add(curr);
    }
  }

  // this used to be computed lazily, but that was clearly erroneous for multithreading!
  @Override
  public int domainDimension() {
    return domainDimension;
  }

  // TODO(mengqiu) add dimension checks
  public void combine2DArr(double[][] combineInto, Map<Integer, double[]> toBeCombined) {
    double[] source = null;
    int key = 0;
    for (Map.Entry<Integer, double[]> entry: toBeCombined.entrySet()) {
      key = entry.getKey();
      source = entry.getValue();
      for (int i = 0; i< source.length; i++)
        combineInto[key][i] += source[i];
    }
  }

  public void combine2DArr(double[][] combineInto, Map<Integer, double[]> toBeCombined, double scale) {
    double[] source = null;
    int key = 0;
    for (Map.Entry<Integer, double[]> entry: toBeCombined.entrySet()) {
      key = entry.getKey();
      source = entry.getValue();
      for (int i = 0; i< source.length; i++)
        combineInto[key][i] += source[i] * scale;
    }
  }

  /**
   * Takes a double array of weights and creates a 2D array where:
   *
   * the first element is the mapped index of the clique size (e.g., node-0, edge-1) matcing featuresIndex i
   * the second element is the number of output classes for that clique size
   *
   * @return a 2D weight array
   */
  public double[][] to2D(double[] weights, List<Index<CRFLabel>> labelIndices, int[] map) {
    double[][] newWeights = new double[map.length][];
    int index = 0;
    for (int i = 0; i < map.length; i++) {
      int labelSize = labelIndices.get(map[i]).size();
      newWeights[i] = new double[labelSize];
      try {
        System.arraycopy(weights, index, newWeights[i], 0, labelSize);
      } catch (Exception ex) {
        System.err.println("weights: " + weights);
        System.err.println("newWeights["+i+"]: " + newWeights[i]);
        throw new RuntimeException(ex);
      }
      index += labelSize;
    }
    return newWeights;
  }

  public double[][] to2D(double[] weights) {
    return to2D(weights, this.labelIndices, this.map);
  }

  /** Beware: this changes the input weights array in place. */
  public double[][] to2D(double[] weights, double wscale) {
    for (int i = 0; i < weights.length; i++)
      weights[i] = weights[i] * wscale;

    return to2D(weights, this.labelIndices, this.map);
  }

  public static double[] to1D(double[][] weights, int domainDimension) {
    double[] newWeights = new double[domainDimension];
    int index = 0;
    for (double[] weightVector : weights) {
      System.arraycopy(weightVector, 0, newWeights, index, weightVector.length);
      index += weightVector.length;
    }
    return newWeights;
  }

  public double[] to1D(double[][] weights) {
    return to1D(weights, domainDimension());
  }

  public int[][] getWeightIndices()
  {
    if (weightIndices == null) {
      weightIndices = new int[map.length][];
      int index = 0;
      for (int i = 0; i < map.length; i++) {
        weightIndices[i] = new int[labelIndices.get(map[i]).size()];
        for (int j = 0; j < labelIndices.get(map[i]).size(); j++) {
          weightIndices[i][j] = index;
          index++;
        }
      }
    }
    return weightIndices;
  }

  private double[][] empty2D() {
    double[][] d = new double[map.length][];
    // int index = 0;
    for (int i = 0; i < map.length; i++) {
      d[i] = new double[labelIndices.get(map[i]).size()];
    }
    return d;
  }

  private void empiricalCounts(double[][] eHat) {
    for (int m = 0; m < data.length; m++) {
      empiricalCountsForADoc(eHat, m);
    }
  }

  private void empiricalCountsForADoc(double[][] eHat, int docIndex) {
    int[][][] docData = data[docIndex];
    int[] docLabels = labels[docIndex];
    int[] windowLabels = new int[window];
    Arrays.fill(windowLabels, classIndex.indexOf(backgroundSymbol));
    double[][][] featureValArr = null;
    if (featureVal != null)
      featureValArr = featureVal[docIndex];

    if (docLabels.length>docData.length) { // only true for self-training
      // fill the windowLabel array with the extra docLabels
      System.arraycopy(docLabels, 0, windowLabels, 0, windowLabels.length);
      // shift the docLabels array left
      int[] newDocLabels = new int[docData.length];
      System.arraycopy(docLabels, docLabels.length-newDocLabels.length, newDocLabels, 0, newDocLabels.length);
      docLabels = newDocLabels;
    }
    for (int i = 0; i < docData.length; i++) {
      System.arraycopy(windowLabels, 1, windowLabels, 0, window - 1);
      windowLabels[window - 1] = docLabels[i];
      for (int j = 0; j < docData[i].length; j++) {
        int[] cliqueLabel = new int[j + 1];
        System.arraycopy(windowLabels, window - 1 - j, cliqueLabel, 0, j + 1);
        CRFLabel crfLabel = new CRFLabel(cliqueLabel);
        int labelIndex = labelIndices.get(j).indexOf(crfLabel);
        //System.err.println(crfLabel + " " + labelIndex);
        for (int n = 0; n < docData[i][j].length; n++) {
          double fVal = 1.0;
          if (featureValArr != null && j == 0) // j == 0 because only node features gets feature values
            fVal = featureValArr[i][j][n];
          eHat[docData[i][j][n]][labelIndex] += fVal;
        }
      }
    }
  }

  public double valueForADoc(double[][] weights, int docIndex) {
    return expectedCountsAndValueForADoc(weights, docIndex, true, false).second();
  }

  private Quadruple<Integer, Double, Map<Integer, double[]>, Map<Integer, double[]>> expectedCountsAndValueForADoc(double[][] weights, int docIndex) {
    return expectedCountsAndValueForADoc(weights, docIndex, false, false);
  }

  private Quadruple<Integer, Double, Map<Integer, double[]>, Map<Integer, double[]>> expectedCountsForADoc(double[][] weights, int docIndex) {
    return expectedCountsAndValueForADoc(weights, docIndex, false, true);
  }

  @Override
  public CliquePotentialFunction getCliquePotentialFunction(double[] x) {
    double[][] weights = to2D(x);
    return new LinearCliquePotentialFunction(weights);
  }

  private Map<Integer, double[]> sparseE(Set<Integer> activeFeatures) {
    Map<Integer, double[]> aMap = new HashMap<Integer, double[]>(activeFeatures.size());
    for (int f: activeFeatures) {
      // System.err.printf("aMap.put(%d, new double[%d])\n", f, map[f]+1);
      aMap.put(f,new double[map[f] == 0 ? nodeLabelIndexSize : edgeLabelIndexSize]);
    }
    return aMap;
  }

  private Map<Integer, double[]> sparseE(int[] activeFeatures) {
    Map<Integer, double[]> aMap = new HashMap<Integer, double[]>(activeFeatures.length);
    for (int f: activeFeatures) {
      // System.err.printf("aMap.put(%d, new double[%d])\n", f, map[f]+1);
      aMap.put(f,new double[map[f] == 0 ? nodeLabelIndexSize : edgeLabelIndexSize]);
    }
    return aMap;
  }

  // TODO(mengqiu) optimize EForADoc and dropoutPriorGrad to be smaller
  private Quadruple<Integer, Double, Map<Integer, double[]>, Map<Integer, double[]>> expectedCountsAndValueForADoc(double[][] weights, int docIndex, 
      boolean skipExpectedCountCalc, boolean skipValCalc) {
    
    int[] activeFeatures = dataFeatureHashByDoc[docIndex];
    List<Set<Integer>> docDataHash = dataFeatureHash.get(docIndex);
    Map<Integer, List<Integer>> condensedFeaturesMap = condensedMap.get(docIndex);

    double prob = 0;
    int[][][] docData = totalData[docIndex];
    int[] docLabels = null;
    if (docIndex < labels.length)
      docLabels = labels[docIndex];

    Timing timer = new Timing();
    double[][][] featureVal3DArr = null;
    if (featureVal != null)
      featureVal3DArr = featureVal[docIndex];

    CliquePotentialFunction cliquePotentialFunc = new LinearCliquePotentialFunction(weights);
    // make a clique tree for this document
    CRFCliqueTree cliqueTree = CRFCliqueTree.getCalibratedCliqueTree(docData, labelIndices, numClasses, classIndex, backgroundSymbol, cliquePotentialFunc, featureVal3DArr);

    if (!skipValCalc) {
      if (TIMED)
        timer.start();
      // compute the log probability of the document given the model with the parameters x
      int[] given = new int[window - 1];
      Arrays.fill(given, classIndex.indexOf(backgroundSymbol));
      if (docLabels.length>docData.length) { // only true for self-training
        // fill the given array with the extra docLabels
        System.arraycopy(docLabels, 0, given, 0, given.length);
        // shift the docLabels array left
        int[] newDocLabels = new int[docData.length];
        System.arraycopy(docLabels, docLabels.length-newDocLabels.length, newDocLabels, 0, newDocLabels.length);
        docLabels = newDocLabels;
      }

      double startPosLogProb = cliqueTree.logProbStartPos();
      if (VERBOSE)
        System.err.printf("P_-1(Background) = % 5.3f\n", startPosLogProb);
      prob += startPosLogProb;

      // iterate over the positions in this document
      for (int i = 0; i < docData.length; i++) {
        int label = docLabels[i];
        double p = cliqueTree.condLogProbGivenPrevious(i, label, given);
        if (VERBOSE) {
          System.err.println("P(" + label + "|" + ArrayMath.toString(given) + ")=" + Math.exp(p));
        }
        prob += p;
        System.arraycopy(given, 1, given, 0, given.length - 1);
        given[given.length - 1] = label;
      }
      if (TIMED) {
        long elapsedMs = timer.stop();
        System.err.println("Calculate objective took: " + Timing.toMilliSecondsString(elapsedMs) + " ms");
      }
    }

    Map<Integer, double[]> EForADoc = sparseE(activeFeatures);
    List<Map<Integer, double[]>> EForADocPos = null;
    if (dropoutApprox) {
      EForADocPos = new ArrayList<Map<Integer, double[]>>(docData.length);
    }
      
    if (!skipExpectedCountCalc) {
      if (TIMED)
        timer.start();
      // compute the expected counts for this document, which we will need to compute the derivative
      // iterate over the positions in this document
      double fVal = 1.0;
      for (int i = 0; i < docData.length; i++) {
        Set<Integer> docDataHashI = docDataHash.get(i);
        Map<Integer, double[]> EForADocPosAtI = null;
        if (dropoutApprox)
          EForADocPosAtI = sparseE(docDataHashI);

        for (int fIndex: docDataHashI) {
          int j= map[fIndex];
          Index<CRFLabel> labelIndex = labelIndices.get(j);
          // for each possible labeling for that clique
          for (int k = 0; k < labelIndex.size(); k++) {
            int[] label = labelIndex.get(k).getLabel();
            double p = cliqueTree.prob(i, label); // probability of these labels occurring in this clique with these features
            if (dropoutApprox)
              increScore(EForADocPosAtI, fIndex, k, fVal * p);
            increScore(EForADoc, fIndex, k, fVal * p);
          }
        }
        if (dropoutApprox) {
          for (int fIndex: docDataHashI) {
            if (condensedFeaturesMap.containsKey(fIndex)) {
              List<Integer> aList = condensedFeaturesMap.get(fIndex);
              for (int toCopyInto: aList) {
                double[] arr = EForADocPosAtI.get(fIndex);
                double[] targetArr = new double[arr.length];
                for (int q=0; q < arr.length; q++)
                  targetArr[q] = arr[q];
                EForADocPosAtI.put(toCopyInto, targetArr);  
              }
            }
          }
        }
        if (dropoutApprox)
          EForADocPos.add(EForADocPosAtI);
      }

      // copy for condensedFeaturesMap
      for (Map.Entry<Integer, List<Integer>> entry: condensedFeaturesMap.entrySet()) {
        int key = entry.getKey();
        List<Integer> aList = entry.getValue();
        for (int toCopyInto: aList) {
          double[] arr = EForADoc.get(key);
          double[] targetArr = new double[arr.length];
          for (int i=0; i < arr.length; i++)
            targetArr[i] = arr[i];
          EForADoc.put(toCopyInto, targetArr);  
        }
      }

      if (TIMED) {
        long elapsedMs = timer.stop();
        System.err.println("Expected count took: " + Timing.toMilliSecondsString(elapsedMs) + " ms");
      }
    }

    Map<Integer, double[]> dropoutPriorGrad = null;
    if (prior == DROPOUT_PRIOR) {
      if (TIMED)
        timer.start();
      // we can optimize this, this is too large, don't need this big
      dropoutPriorGrad = sparseE(activeFeatures);

      // System.err.print("computing dropout prior for doc " + docIndex + " ... ");
      prob -= getDropoutPrior(weights, cliqueTree, docData, EForADoc, docDataHash, activeFeatures, dropoutPriorGrad, condensedFeaturesMap, EForADocPos);
      // System.err.println(" done!");
      if (TIMED) {
        long elapsedMs = timer.stop();
        System.err.println("Dropout took: " + Timing.toMilliSecondsString(elapsedMs) + " ms");
      }
    }

    return new Quadruple<Integer, Double, Map<Integer, double[]>, Map<Integer, double[]>>(docIndex, prob, EForADoc, dropoutPriorGrad);
  }

  private void increScore(Map<Integer, double[]> aMap, int fIndex, int k, double val) {
    aMap.get(fIndex)[k] += val;
  }

  private void increScoreAllowNull(Map<Integer, double[]> aMap, int fIndex, int k, double val) {
    if (!aMap.containsKey(fIndex)) {
      aMap.put(fIndex, new double[map[fIndex] == 0 ? nodeLabelIndexSize : edgeLabelIndexSize]);
    }
    aMap.get(fIndex)[k] += val;
  }

  private void initializeDataFeatureHash() {
    int macroActiveFeatureTotalCount = 0;
    int macroCondensedTotalCount = 0;
    int macroDocPosCount = 0;

    System.err.println("initializing data feature hash, sup-data size: " + data.length + ", unsup data size: " + (totalData.length-data.length));
    dataFeatureHash = new ArrayList<List<Set<Integer>>>(totalData.length);
    condensedMap = new ArrayList<Map<Integer, List<Integer>>>(totalData.length);
    dataFeatureHashByDoc = new int[totalData.length][];
    for (int m=0; m < totalData.length; m++) {
      Map<Integer, Integer> occurPos = new HashMap<Integer, Integer>();

      int[][][] aDoc = totalData[m];
      List<Set<Integer>> aList = new ArrayList<Set<Integer>>(aDoc.length);
      Set<Integer> setOfFeatures = new HashSet<Integer>();
      for (int i=0; i< aDoc.length; i++) { // positions in docI
        Set<Integer> aSet = new HashSet<Integer>();
        int[][] dataI = aDoc[i];
        for (int j=0; j < dataI.length; j++) {
          int[] dataJ = dataI[j];
          for (int item: dataJ) { 
            if (j == 0) {
              if (occurPos.containsKey(item))
                occurPos.put(item, -1);
              else
                occurPos.put(item, i);
            }
            
            aSet.add(item);
          }
        }
        aList.add(aSet);
        setOfFeatures.addAll(aSet);
      }
      macroDocPosCount += aDoc.length;
      macroActiveFeatureTotalCount += setOfFeatures.size();

      if (CONDENSE) {
        if (DEBUG3)
          System.err.println("Before condense, activeFeatures = " + setOfFeatures.size());
        // examine all singletons, merge ones in the same position
        Map<Integer, List<Integer>> condensedFeaturesMap = new HashMap<Integer, List<Integer>>();
        int[] representFeatures = new int[aDoc.length];
        Arrays.fill(representFeatures, -1);

        int key, pos = 0;
        for (Map.Entry<Integer, Integer> entry: occurPos.entrySet()) {
          key = entry.getKey();
          pos = entry.getValue();
          if (pos != -1) {
            if (representFeatures[pos] == -1) { // use this as representFeatures
              representFeatures[pos] = key;
              condensedFeaturesMap.put(key, new ArrayList<Integer>());
            } else { // condense this one
              int rep = representFeatures[pos];
              condensedFeaturesMap.get(rep).add(key);
              // remove key
              aList.get(pos).remove(key);
              setOfFeatures.remove(key);
            }
          }
        }
        int condensedCount = 0;
        for(Iterator<Map.Entry<Integer, List<Integer>>> it = condensedFeaturesMap.entrySet().iterator(); it.hasNext(); ) {
          Map.Entry<Integer, List<Integer>> entry = it.next();
          if(entry.getValue().size() == 0) {
            it.remove();
          } else {
            if (DEBUG3) {
              condensedCount += entry.getValue().size();
              for (int cond: entry.getValue())
                System.err.println("condense " + cond + " to " + entry.getKey());
            }
          }
        }
        if (DEBUG3)
          System.err.println("After condense, activeFeatures = " + setOfFeatures.size() + ", condensedCount = " + condensedCount);
        macroCondensedTotalCount += setOfFeatures.size();
        condensedMap.add(condensedFeaturesMap);
      }

      dataFeatureHash.add(aList);
      int[] arrOfIndex = new int[setOfFeatures.size()];
      int pos2 = 0;
      for(Integer ind: setOfFeatures)
        arrOfIndex[pos2++] = ind;
      dataFeatureHashByDoc[m] = arrOfIndex;
    }
    System.err.println("Avg. active features per position: " + (macroActiveFeatureTotalCount/ (macroDocPosCount+0.0)));
    System.err.println("Avg. condensed features per position: " + (macroCondensedTotalCount / (macroDocPosCount+0.0)));
    System.err.println("initializing data feature hash done!"); 
  }

  private double getDropoutPrior(double[][] weights, CRFCliqueTree cliqueTree, int[][][] docData, 
      Map<Integer, double[]> EForADoc, List<Set<Integer>> docDataHash, int[] activeFeatures, Map<Integer, double[]> dropoutPriorGrad,
      Map<Integer, List<Integer>> condensedFeaturesMap, List<Map<Integer, double[]>> EForADocPos) {

    Map<Integer, double[]> dropoutPriorGradFirstHalf = sparseE(activeFeatures);

    if (TIMED)  
      System.err.println("activeFeatures size: "+activeFeatures.length + ", dataLen: " + docData.length);

    Timing timer = new Timing();
    if (TIMED)
      timer.start();

    double priorValue = 0;

    // first index position is curr index, second index curr-class, third index prev-class
    // e.g. [1][2][3] means curr is at position 1 with class 2, prev is at position 0 with class 3
    double[][][] prevGivenCurr = new double[docData.length][][]; 
    // first index position is curr index, second index curr-class, third index next-class
    // e.g. [0][2][3] means curr is at position 0 with class 2, next is at position 1 with class 3
    double[][][] nextGivenCurr = new double[docData.length][][]; 

    // first dim is doc length (i)
    // second dim is numOfFeatures (fIndex)
    // third dim is numClasses (y)
    // fourth dim is labelIndexSize (matching the clique type of fIndex, for \theta)
    double[][][][] FAlpha = null;
    double[][][][] FBeta  = null;
    if (!dropoutApprox) {
      FAlpha = new double[docData.length][][][];
      FBeta  = new double[docData.length][][][];
    }
    for (int i = 0; i < docData.length; i++) {
      if (!dropoutApprox) {
        FAlpha[i] = new double[activeFeatures.length][][];
        FBeta[i]  = new double[activeFeatures.length][][];
      }
      // for (int j = 0; j < map.length; j++) {
      //   FAlpha[i][j] = new double[numClasses];
      //   FBeta[i][j] = new double[numClasses];
      // }
      prevGivenCurr[i] = new double[numClasses][]; 
      nextGivenCurr[i] = new double[numClasses][]; 
      for (int j = 0; j < numClasses; j++) {
        prevGivenCurr[i][j] = new double[numClasses];
        nextGivenCurr[i][j] = new double[numClasses];
      }
    }

    long elapsedMs = 0;
    if (TIMED) {
      elapsedMs = timer.stop();
      System.err.println("\t initialization took: " + Timing.toMilliSecondsString(elapsedMs) + " ms");
      timer.start();
    }
    // computing prevGivenCurr and nextGivenCurr
    for (int i=0; i < docData.length; i++) {
      int[] labelPair = new int[2];
      for (int l1 = 0; l1 < numClasses; l1++) {
        labelPair[0] = l1;
        for (int l2 = 0; l2 < numClasses; l2++) {
          labelPair[1] = l2;
          double prob = cliqueTree.logProb(i, labelPair);
          // System.err.println(prob);
          if (i-1 >= 0)
            nextGivenCurr[i-1][l1][l2] = prob;
          prevGivenCurr[i][l2][l1] = prob;
        }
      }

      if (DEBUG2) {
        System.err.println("unnormalized conditionals:");
        if (i>0) {
        System.err.println("nextGivenCurr[" + (i-1) + "]:");
        for (int a = 0; a < nextGivenCurr[i-1].length; a++) {
          for (int b = 0; b < nextGivenCurr[i-1][a].length; b++)
            System.err.print((nextGivenCurr[i-1][a][b])+"\t");
          System.err.println();
        }
        }
        System.err.println("prevGivenCurr[" + (i) + "]:");
        for (int a = 0; a < prevGivenCurr[i].length; a++) {
          for (int b = 0; b < prevGivenCurr[i][a].length; b++)
            System.err.print((prevGivenCurr[i][a][b])+"\t");
          System.err.println();
        }
      }

      for (int j=0; j< numClasses; j++) {
        if (i-1 >= 0) {
          // ArrayMath.normalize(nextGivenCurr[i-1][j]);
          ArrayMath.logNormalize(nextGivenCurr[i-1][j]);
          for (int k = 0; k < nextGivenCurr[i-1][j].length; k++)
            nextGivenCurr[i-1][j][k] = Math.exp(nextGivenCurr[i-1][j][k]);
        }
        // ArrayMath.normalize(prevGivenCurr[i][j]);
        ArrayMath.logNormalize(prevGivenCurr[i][j]);
        for (int k = 0; k < prevGivenCurr[i][j].length; k++)
          prevGivenCurr[i][j][k] = Math.exp(prevGivenCurr[i][j][k]);
      }

      if (DEBUG2) {
        System.err.println("normalized conditionals:");
        if (i>0) {
        System.err.println("nextGivenCurr[" + (i-1) + "]:");
        for (int a = 0; a < nextGivenCurr[i-1].length; a++) {
          for (int b = 0; b < nextGivenCurr[i-1][a].length; b++)
            System.err.print((nextGivenCurr[i-1][a][b])+"\t");
          System.err.println();
        }
        }
        System.err.println("prevGivenCurr[" + (i) + "]:");
        for (int a = 0; a < prevGivenCurr[i].length; a++) {
          for (int b = 0; b < prevGivenCurr[i][a].length; b++)
            System.err.print((prevGivenCurr[i][a][b])+"\t");
          System.err.println();
        }
      }
    }

    if (TIMED) {
      elapsedMs = timer.stop();
      System.err.println("\t cond prob took: " + Timing.toMilliSecondsString(elapsedMs) + " ms");
    }

    if (!dropoutApprox) {
      if (TIMED) {
        timer.start();
      }
      // computing FAlpha
      int fIndex = 0;
      double aa, bb, cc = 0;
      boolean prevFeaturePresent  = false;
      for (int i = 1; i < docData.length; i++) {
        // for each possible clique at this position
        Set<Integer> docDataHashIMinusOne = docDataHash.get(i-1);
        for (int fIndexPos = 0; fIndexPos < activeFeatures.length; fIndexPos++) {
          fIndex = activeFeatures[fIndexPos];
          prevFeaturePresent = docDataHashIMinusOne.contains(fIndex);
          int j = map[fIndex];
          Index<CRFLabel> labelIndex = labelIndices.get(j);
          int labelIndexSize = labelIndex.size();

          if (FAlpha[i-1][fIndexPos] == null) {
            FAlpha[i-1][fIndexPos] = new double[numClasses][labelIndexSize];
            for (int q = 0; q < numClasses; q++)
              FAlpha[i-1][fIndexPos][q] = new double[labelIndexSize]; 
          }

          for (Map.Entry<Integer, List<Integer>> entry : currPrevLabelsMap.entrySet()) {
            int y = entry.getKey(); // value at i-1
            double[] sum = new double[labelIndexSize];
            for (int yPrime: entry.getValue()) { // value at i-2
              for (int kk = 0; kk < labelIndexSize; kk++) {
                int[] prevLabel = labelIndex.get(kk).getLabel();
                aa = (prevGivenCurr[i-1][y][yPrime]);
                bb = (prevFeaturePresent && ((j == 0 && prevLabel[0] == y) || (j == 1 && prevLabel[1] == y && prevLabel[0] == yPrime)) ? 1 : 0);
                cc = 0;
                if (FAlpha[i-1][fIndexPos][yPrime] != null)
                  cc = FAlpha[i-1][fIndexPos][yPrime][kk];
                sum[kk] +=  aa * (bb + cc);
                // sum[kk] += (prevGivenCurr[i-1][y][yPrime]) * ((prevFeaturePresent && ((j == 0 && prevLabel[0] == y) || (j == 1 && prevLabel[1] == y && prevLabel[0] == yPrime)) ? 1 : 0) + FAlpha[i-1][fIndexPos][yPrime][kk]);
                if (DEBUG2)
                  System.err.printf("alpha[%d][%d][%d][%d] += % 5.3f * (%d + % 5.3f), prevLabel=%s\n", i, fIndex, y, kk, (prevGivenCurr[i-1][y][yPrime]), (prevFeaturePresent && ((j == 0 && prevLabel[0] == y) || (j == 1 && prevLabel[1] == y && prevLabel[0] == yPrime)) ? 1 : 0) , FAlpha[i-1][fIndexPos][yPrime][kk], Arrays.toString(prevLabel));
              }
            }
            if (FAlpha[i][fIndexPos] == null) {
              FAlpha[i][fIndexPos] = new double[numClasses][];
            }
            FAlpha[i][fIndexPos][y] = sum;
            if (DEBUG2)
              System.err.println("FAlpha["+i+"]["+fIndexPos+"]["+y+"] = " + Arrays.toString(sum));
            
          }
        }
      }
      if (TIMED) {
        elapsedMs = timer.stop();
        System.err.println("\t alpha took: " + Timing.toMilliSecondsString(elapsedMs) + " ms");
        timer.start();
      }
      // computing FBeta
      int docDataLen = docData.length;
      boolean nextFeaturePresent = false;
      for (int i = docDataLen-2; i >= 0; i--) {
        Set<Integer> docDataHashIPlusOne = docDataHash.get(i+1);
        // for each possible clique at this position
        for (int fIndexPos = 0; fIndexPos < activeFeatures.length; fIndexPos++) {
          fIndex = activeFeatures[fIndexPos];
          nextFeaturePresent = docDataHashIPlusOne.contains(fIndex);
          int j = map[fIndex];
          Index<CRFLabel> labelIndex = labelIndices.get(j);
          int labelIndexSize = labelIndex.size();

          if (FBeta[i+1][fIndexPos] == null) {
            FBeta[i+1][fIndexPos] = new double[numClasses][labelIndexSize];
            for (int q = 0; q < numClasses; q++)
              FBeta[i+1][fIndexPos][q] = new double[labelIndexSize]; 
          }

          for (Map.Entry<Integer, List<Integer>> entry : currNextLabelsMap.entrySet()) {
            int y = entry.getKey(); // value at i
            double[] sum = new double[labelIndexSize];
            for (int yPrime: entry.getValue()) { // value at i+1
              for (int kk=0; kk < labelIndexSize; kk++) {
                int[] nextLabel = labelIndex.get(kk).getLabel();
                // System.err.println("labelIndexSize:"+labelIndexSize+", nextGivenCurr:"+nextGivenCurr+", nextLabel:"+nextLabel+", FBeta["+(i+1)+"]["+ fIndexPos +"]["+yPrime+"] :"+FBeta[i+1][fIndexPos][yPrime]);
                aa = (nextGivenCurr[i][y][yPrime]);
                bb = (nextFeaturePresent && ((j == 0 && nextLabel[0] == yPrime) || (j == 1 && nextLabel[0] == y && nextLabel[1] == yPrime)) ? 1 : 0);
                cc = 0;
                if (FBeta[i+1][fIndexPos][yPrime] != null)
                  cc = FBeta[i+1][fIndexPos][yPrime][kk];
                sum[kk] +=  aa * ( bb + cc);
                // sum[kk] += (nextGivenCurr[i][y][yPrime]) * ( (nextFeaturePresent && ((j == 0 && nextLabel[0] == yPrime) || (j == 1 && nextLabel[0] == y && nextLabel[1] == yPrime)) ? 1 : 0) + FBeta[i+1][fIndexPos][yPrime][kk]);
                if (DEBUG2)
                  System.err.printf("beta[%d][%d][%d][%d] += % 5.3f * (%d + % 5.3f)\n", i, fIndex, y, kk, (nextGivenCurr[i][y][yPrime]), (nextFeaturePresent && ((j == 0 && nextLabel[0] == yPrime) || (j == 1 && nextLabel[0] == y && nextLabel[1] == yPrime)) ? 1 : 0), FBeta[i+1][fIndexPos][yPrime][kk]);
              }
            }
            if (FBeta[i][fIndexPos] == null) {
              FBeta[i][fIndexPos] = new double[numClasses][];
            }
            FBeta[i][fIndexPos][y] = sum;
            if (DEBUG2)
              System.err.println("FBeta["+i+"]["+fIndexPos+"]["+y+"] = " + Arrays.toString(sum));
          }
        }
      }
      if (TIMED) {
        elapsedMs = timer.stop();
        System.err.println("\t beta took: " + Timing.toMilliSecondsString(elapsedMs) + " ms");
      }
    }
    if (TIMED) {
      timer.start();
    }

    // derivative equals: VarU' * PtYYp * (1-PtYYp) + VarU * PtYYp' * (1-PtYYp) + VarU * PtYYp * (1-PtYYp)'
    // derivative equals: VarU' * PtYYp * (1-PtYYp) + VarU * PtYYp' * (1-PtYYp) + VarU * PtYYp * -PtYYp'
    // derivative equals: VarU' * PtYYp * (1-PtYYp) + VarU * PtYYp' * (1 - 2 * PtYYp)

    double deltaDivByOneMinusDelta = delta / (1.0-delta);

    Timing innerTimer = new Timing();
    long eTiming = 0;
    long dropoutTiming= 0;

    double PtYYp, PtYYpTimesOneMinusPtYYp, oneMinus2PtYYp, USum, theta, VarUp, VarU, VarUTimesOneMinus2PtYYp = 0.0;
    int fIndex, valIndex = 0;
    boolean containsFeature = false;
    // iterate over the positions in this document
    for (int i = 1; i < docData.length; i++) {
      Set<Integer> docDataHashI = docDataHash.get(i);
      Map<Integer, double[]> EForADocPosAtI = null;
      if (dropoutApprox)
        EForADocPosAtI = EForADocPos.get(i);

      // for each possible clique at this position
      for (int k = 0; k < edgeLabelIndexSize; k++) { // sum over (y, y')
        int[] label = edgeLabels[k];
        int y = label[0];
        int yP = label[1];

        if (TIMED)
          innerTimer.start();

        // important to use label as an int[] for calculating cliqueTree.prob()
        // if it's a node clique, and label index is 2, if we don't use int[]{2} but just pass 2,
        // cliqueTree is going to treat it as index of the edge clique labels, and convert 2
        // into int[]{0,2}, and return the edge prob marginal instead of node marginal
        PtYYp = cliqueTree.prob(i, label); // probability of these labels occurring in this clique with these features
        PtYYpTimesOneMinusPtYYp = PtYYp * (1.0-PtYYp);
        oneMinus2PtYYp = (1.0 - 2 * PtYYp);
        USum = 0;
        for (int jjj=0; jjj<labelIndices.size(); jjj++) {
          for (int n = 0; n < docData[i][jjj].length; n++) {
            fIndex = docData[i][jjj][n];
            if (jjj == 1)
              valIndex = k;
            else
              valIndex = yP;
            try {
            theta = weights[fIndex][valIndex];
            }catch (Exception ex) {
              System.err.printf("weights[%d][%d], map[%d]=%d, labelIndices.get(map[%d]).size() = %d, weights.length=%d\n", fIndex, valIndex, fIndex, map[fIndex], fIndex, labelIndices.get(map[fIndex]).size(), weights.length);
              throw new RuntimeException(ex);
            }

            USum += weightSquare[fIndex][valIndex];

            // first half of derivative: VarU' * PtYYp * (1-PtYYp) 
            VarUp = deltaDivByOneMinusDelta * theta;
            increScoreAllowNull(dropoutPriorGradFirstHalf, fIndex, valIndex, VarUp * PtYYpTimesOneMinusPtYYp);
            // dropoutPriorGradFirstHalf[fIndex][valIndex] += VarUp * PtYYpTimesOneMinusPtYYp;
            // dropoutPriorGrad[fIndex][k] += VarUp;
          }
        }
        
        if (TIMED) {
          eTiming += innerTimer.stop();
          innerTimer.start();
        }
        VarU = 0.5 * deltaDivByOneMinusDelta * USum;

        // double filter = (k == 3 ? 1 : 0);
        // double filter = (k == 3 ? 1 : 0);
        // double filter = k+1;

        // update function objective
        priorValue += VarU * PtYYpTimesOneMinusPtYYp;
        // priorValue += (k == 0 && j == 1 ? 1 : 0) * PtYYp;
        // priorValue += filter * PtYYp;
        // if (DEBUG2)
        //   System.err.println("priorValue += "+  filter * PtYYp);
        // priorValue += PtYYp;

        VarUTimesOneMinus2PtYYp = VarU * oneMinus2PtYYp; 

        // second half of derivative: VarU * PtYYp' * (1 - 2 * PtYYp)
        int jj = 0;
        int[] fLabel = null;
        double alpha, beta, fCount, condE, PtYYpPrime = 0;
        boolean prevFeaturePresent = false;
        boolean nextFeaturePresent = false; 
        for (int fIndexPos = 0; fIndexPos < activeFeatures.length; fIndexPos++) {
          fIndex = activeFeatures[fIndexPos];
          containsFeature = docDataHashI.contains(fIndex);

          // if (!containsFeature) continue;
          jj = map[fIndex];
          Index<CRFLabel> fLabelIndex = labelIndices.get(jj);
          for (int kk = 0; kk < fLabelIndex.size(); kk++) { // for all parameter \theta
            fLabel = fLabelIndex.get(kk).getLabel();
            // if (FAlpha[i] != null)
            //   System.err.println("fIndex: " + fIndex+", FAlpha[i].size:"+FAlpha[i].length);
            fCount = containsFeature && ((jj == 0 && fLabel[0] == yP) || (jj == 1 && k == kk)) ? 1 : 0;

            if (!dropoutApprox) {
              alpha = ((FAlpha[i][fIndexPos] == null || FAlpha[i][fIndexPos][y] == null) ? 0 : FAlpha[i][fIndexPos][y][kk]);
              beta = ((FBeta[i][fIndexPos] == null || FBeta[i][fIndexPos][yP] == null) ? 0 : FBeta[i][fIndexPos][yP][kk]);
              condE = fCount + alpha + beta;
              if (DEBUG2)
                System.err.printf("fLabel=%s, yP = %d, fCount:%f = ((jj == 0 && fLabel[0] == yP)=%b || (jj == 1 && k == kk))=%b\n", Arrays.toString(fLabel),yP, fCount,(jj == 0 && fLabel[0] == yP) , (jj == 1 && k == kk));
              PtYYpPrime = PtYYp * (condE - EForADoc.get(fIndex)[kk]);
            } else {
              double E = 0;
              if (EForADocPosAtI.containsKey(fIndex))
                E = EForADocPosAtI.get(fIndex)[kk];
              condE = fCount;
              PtYYpPrime = PtYYp * (condE - E);
            }

            if (DEBUG2)
              System.err.printf("for i=%d, k=%d, y=%d, yP=%d, fIndex=%d, kk=%d, PtYYpPrime=% 5.3f, PtYYp=% 3.3f, (condE-E[fIndex][kk])=% 3.3f, condE=% 3.3f, E[fIndex][k]=% 3.3f, alpha=% 3.3f, beta=% 3.3f, fCount=% 3.3f\n", i, k, y, yP, fIndex, kk, PtYYpPrime, PtYYp, (condE - EForADoc.get(fIndex)[kk]), condE, EForADoc.get(fIndex)[kk], alpha, beta, fCount);

            increScore(dropoutPriorGrad, fIndex, kk, VarUTimesOneMinus2PtYYp * PtYYpPrime);
            // dropoutPriorGrad[fIndex][kk] += (k == 0 && j == 1 ? 1 : 0) * PtYYpPrime;
            // dropoutPriorGrad[fIndex][kk] += filter * PtYYpPrime;
            // dropoutPriorGrad[fIndex][kk] += PtYYpPrime;
          }

          if (DEBUG2)
            System.err.println();
        }
        if (dropoutApprox)
          EForADocPos.add(EForADocPosAtI);
      }

      // copy for condensedFeaturesMap
      for (Map.Entry<Integer, List<Integer>> entry: condensedFeaturesMap.entrySet()) {
        int key = entry.getKey();
        List<Integer> aList = entry.getValue();
        for (int toCopyInto: aList) {
          double[] arr = EForADoc.get(key);
          double[] targetArr = new double[arr.length];
          for (int i=0; i < arr.length; i++)
            targetArr[i] = arr[i];
          EForADoc.put(toCopyInto, targetArr);  
        }
      }

      if (TIMED) {
        long elapsedMs = timer.stop();
        System.err.println("Expected count took: " + Timing.toMilliSecondsString(elapsedMs) + " ms");
        if (TIMED)
          dropoutTiming += innerTimer.stop();
      }
    }
    if (CONDENSE) {
      // copy for condensedFeaturesMap
      for (Map.Entry<Integer, List<Integer>> entry: condensedFeaturesMap.entrySet()) {
        int key = entry.getKey();
        List<Integer> aList = entry.getValue();
        for (int toCopyInto: aList) {
          double[] arr = dropoutPriorGrad.get(key);
          double[] targetArr = new double[arr.length];
          for (int i=0; i < arr.length; i++)
            targetArr[i] = arr[i];
          dropoutPriorGrad.put(toCopyInto, targetArr);
        }
      }
    }

    if (DEBUG3) {
      System.err.print("dropoutPriorGradFirstHalf.keys:[");
      for (int key: dropoutPriorGradFirstHalf.keySet())
        System.err.print(" "+key);
      System.err.println("]");

    Map<Integer, double[]> dropoutPriorGrad = null;
    if (prior == DROPOUT_PRIOR) {
      if (TIMED)
        timer.start();
      // we can optimize this, this is too large, don't need this big
      dropoutPriorGrad = sparseE(activeFeatures);

      // System.err.print("computing dropout prior for doc " + docIndex + " ... ");
      prob -= getDropoutPrior(weights, cliqueTree, docData, EForADoc, docDataHash, activeFeatures, dropoutPriorGrad, condensedFeaturesMap, EForADocPos);
      // System.err.println(" done!");
      if (TIMED) {
        long elapsedMs = timer.stop();
        System.err.println("Dropout took: " + Timing.toMilliSecondsString(elapsedMs) + " ms");
      }
    }

    return new Quadruple<Integer, Double, Map<Integer, double[]>, Map<Integer, double[]>>(docIndex, prob, EForADoc, dropoutPriorGrad);
  }

  private void increScore(Map<Integer, double[]> aMap, int fIndex, int k, double val) {
    aMap.get(fIndex)[k] += val;
  }

  private void increScoreAllowNull(Map<Integer, double[]> aMap, int fIndex, int k, double val) {
    if (!aMap.containsKey(fIndex)) {
      aMap.put(fIndex, new double[map[fIndex] == 0 ? nodeLabelIndexSize : edgeLabelIndexSize]);
    }
    aMap.get(fIndex)[k] += val;
  }

  private void initializeDataFeatureHash() {
    System.err.println("initializing data feature hash, sup-data size: " + data.length + ", unsup data size: " + (totalData.length-data.length));
    dataFeatureHash = new ArrayList<List<Set<Integer>>>(totalData.length);
    condensedMap = new ArrayList<Map<Integer, List<Integer>>>(totalData.length);
    dataFeatureHashByDoc = new int[totalData.length][];
    for (int m=0; m < totalData.length; m++) {
      Map<Integer, Integer> occurPos = new HashMap<Integer, Integer>();

      int[][][] aDoc = totalData[m];
      List<Set<Integer>> aList = new ArrayList<Set<Integer>>(aDoc.length);
      Set<Integer> setOfFeatures = new HashSet<Integer>();
      for (int i=0; i< aDoc.length; i++) { // positions in docI
        Set<Integer> aSet = new HashSet<Integer>();
        int[][] dataI = aDoc[i];
        for (int j=0; j < dataI.length; j++) {
          int[] dataJ = dataI[j];
          for (int item: dataJ) { 
            if (j == 0) {
              if (occurPos.containsKey(item))
                occurPos.put(item, -1);
              else
                occurPos.put(item, i);
            }
            
            aSet.add(item);
          }
        }
        aList.add(aSet);
        setOfFeatures.addAll(aSet);
      }

      if (CONDENSE) {
        if (DEBUG3)
          System.err.println("Before condense, activeFeatures = " + setOfFeatures.size());
        // examine all singletons, merge ones in the same position
        Map<Integer, List<Integer>> condensedFeaturesMap = new HashMap<Integer, List<Integer>>();
        int[] representFeatures = new int[aDoc.length];
        Arrays.fill(representFeatures, -1);

        int key, pos = 0;
        for (Map.Entry<Integer, Integer> entry: occurPos.entrySet()) {
          key = entry.getKey();
          pos = entry.getValue();
          if (pos != -1) {
            if (representFeatures[pos] == -1) { // use this as representFeatures
              representFeatures[pos] = key;
              condensedFeaturesMap.put(key, new ArrayList<Integer>());
            } else { // condense this one
              int rep = representFeatures[pos];
              condensedFeaturesMap.get(rep).add(key);
              // remove key
              aList.get(pos).remove(key);
              setOfFeatures.remove(key);
            }
          }
        }
        int condensedCount = 0;
        for(Iterator<Map.Entry<Integer, List<Integer>>> it = condensedFeaturesMap.entrySet().iterator(); it.hasNext(); ) {
          Map.Entry<Integer, List<Integer>> entry = it.next();
          if(entry.getValue().size() == 0) {
            it.remove();
          } else {
            if (DEBUG3) {
              condensedCount += entry.getValue().size();
              for (int cond: entry.getValue())
                System.err.println("condense " + cond + " to " + entry.getKey());
            }
          }
        }
        if (DEBUG3)
          System.err.println("After condense, activeFeatures = " + setOfFeatures.size() + ", condensedCount = " + condensedCount);
        condensedMap.add(condensedFeaturesMap);
      }

      dataFeatureHash.add(aList);
      int[] arrOfIndex = new int[setOfFeatures.size()];
      int pos2 = 0;
      for(Integer ind: setOfFeatures)
        arrOfIndex[pos2++] = ind;
      dataFeatureHashByDoc[m] = arrOfIndex;
    }
    System.err.println("initializing data feature hash done!"); 
  }

  private double getDropoutPrior(double[][] weights, CRFCliqueTree cliqueTree, int[][][] docData, 
      Map<Integer, double[]> EForADoc, List<Set<Integer>> docDataHash, int[] activeFeatures, Map<Integer, double[]> dropoutPriorGrad,
      Map<Integer, List<Integer>> condensedFeaturesMap, List<Map<Integer, double[]>> EForADocPos) {

    Map<Integer, double[]> dropoutPriorGradFirstHalf = sparseE(activeFeatures);

    if (TIMED)  
      System.err.println("activeFeatures size: "+activeFeatures.length + ", dataLen: " + docData.length);

    Timing timer = new Timing();
    if (TIMED)
      timer.start();

    double priorValue = 0;

    // first index position is curr index, second index curr-class, third index prev-class
    // e.g. [1][2][3] means curr is at position 1 with class 2, prev is at position 0 with class 3
    double[][][] prevGivenCurr = new double[docData.length][][]; 
    // first index position is curr index, second index curr-class, third index next-class
    // e.g. [0][2][3] means curr is at position 0 with class 2, next is at position 1 with class 3
    double[][][] nextGivenCurr = new double[docData.length][][]; 

    // first dim is doc length (i)
    // second dim is numOfFeatures (fIndex)
    // third dim is numClasses (y)
    // fourth dim is labelIndexSize (matching the clique type of fIndex, for \theta)
    double[][][][] FAlpha = null;
    double[][][][] FBeta  = null;
    if (!dropoutApprox) {
      FAlpha = new double[docData.length][][][];
      FBeta  = new double[docData.length][][][];
    }
    for (int i = 0; i < docData.length; i++) {
      if (!dropoutApprox) {
        FAlpha[i] = new double[activeFeatures.length][][];
        FBeta[i]  = new double[activeFeatures.length][][];
      }
      // for (int j = 0; j < map.length; j++) {
      //   FAlpha[i][j] = new double[numClasses];
      //   FBeta[i][j] = new double[numClasses];
      // }
      prevGivenCurr[i] = new double[numClasses][]; 
      nextGivenCurr[i] = new double[numClasses][]; 
      for (int j = 0; j < numClasses; j++) {
        prevGivenCurr[i][j] = new double[numClasses];
        nextGivenCurr[i][j] = new double[numClasses];
      }
    }

    long elapsedMs = 0;
    if (TIMED) {
      elapsedMs = timer.stop();
      System.err.println("\t initialization took: " + Timing.toMilliSecondsString(elapsedMs) + " ms");
      timer.start();
    }
    // computing prevGivenCurr and nextGivenCurr
    for (int i=0; i < docData.length; i++) {
      int[] labelPair = new int[2];
      for (int l1 = 0; l1 < numClasses; l1++) {
        labelPair[0] = l1;
        for (int l2 = 0; l2 < numClasses; l2++) {
          labelPair[1] = l2;
          double prob = cliqueTree.logProb(i, labelPair);
          // System.err.println(prob);
          if (i-1 >= 0)
            nextGivenCurr[i-1][l1][l2] = prob;
          prevGivenCurr[i][l2][l1] = prob;
        }
      }

      if (DEBUG2) {
        System.err.println("unnormalized conditionals:");
        if (i>0) {
        System.err.println("nextGivenCurr[" + (i-1) + "]:");
        for (int a = 0; a < nextGivenCurr[i-1].length; a++) {
          for (int b = 0; b < nextGivenCurr[i-1][a].length; b++)
            System.err.print((nextGivenCurr[i-1][a][b])+"\t");
          System.err.println();
        }
        }
        System.err.println("prevGivenCurr[" + (i) + "]:");
        for (int a = 0; a < prevGivenCurr[i].length; a++) {
          for (int b = 0; b < prevGivenCurr[i][a].length; b++)
            System.err.print((prevGivenCurr[i][a][b])+"\t");
          System.err.println();
        }
      }

      for (int j=0; j< numClasses; j++) {
        if (i-1 >= 0) {
          // ArrayMath.normalize(nextGivenCurr[i-1][j]);
          ArrayMath.logNormalize(nextGivenCurr[i-1][j]);
          for (int k = 0; k < nextGivenCurr[i-1][j].length; k++)
            nextGivenCurr[i-1][j][k] = Math.exp(nextGivenCurr[i-1][j][k]);
        }
        // ArrayMath.normalize(prevGivenCurr[i][j]);
        ArrayMath.logNormalize(prevGivenCurr[i][j]);
        for (int k = 0; k < prevGivenCurr[i][j].length; k++)
          prevGivenCurr[i][j][k] = Math.exp(prevGivenCurr[i][j][k]);
      }

      if (DEBUG2) {
        System.err.println("normalized conditionals:");
        if (i>0) {
        System.err.println("nextGivenCurr[" + (i-1) + "]:");
        for (int a = 0; a < nextGivenCurr[i-1].length; a++) {
          for (int b = 0; b < nextGivenCurr[i-1][a].length; b++)
            System.err.print((nextGivenCurr[i-1][a][b])+"\t");
          System.err.println();
        }
        }
        System.err.println("prevGivenCurr[" + (i) + "]:");
        for (int a = 0; a < prevGivenCurr[i].length; a++) {
          for (int b = 0; b < prevGivenCurr[i][a].length; b++)
            System.err.print((prevGivenCurr[i][a][b])+"\t");
          System.err.println();
        }
      }
    }

    if (TIMED) {
      elapsedMs = timer.stop();
      System.err.println("\t cond prob took: " + Timing.toMilliSecondsString(elapsedMs) + " ms");
    }

    if (!dropoutApprox) {
      if (TIMED) {
        timer.start();
      }
      // computing FAlpha
      int fIndex = 0;
      double aa, bb, cc = 0;
      boolean prevFeaturePresent  = false;
      for (int i = 1; i < docData.length; i++) {
        // for each possible clique at this position
        Set<Integer> docDataHashIMinusOne = docDataHash.get(i-1);
        for (int fIndexPos = 0; fIndexPos < activeFeatures.length; fIndexPos++) {
          fIndex = activeFeatures[fIndexPos];
          prevFeaturePresent = docDataHashIMinusOne.contains(fIndex);
          int j = map[fIndex];
          Index<CRFLabel> labelIndex = labelIndices.get(j);
          int labelIndexSize = labelIndex.size();

          if (FAlpha[i-1][fIndexPos] == null) {
            FAlpha[i-1][fIndexPos] = new double[numClasses][labelIndexSize];
            for (int q = 0; q < numClasses; q++)
              FAlpha[i-1][fIndexPos][q] = new double[labelIndexSize]; 
          }

          for (Map.Entry<Integer, List<Integer>> entry : currPrevLabelsMap.entrySet()) {
            int y = entry.getKey(); // value at i-1
            double[] sum = new double[labelIndexSize];
            for (int yPrime: entry.getValue()) { // value at i-2
              for (int kk = 0; kk < labelIndexSize; kk++) {
                int[] prevLabel = labelIndex.get(kk).getLabel();
                aa = (prevGivenCurr[i-1][y][yPrime]);
                bb = (prevFeaturePresent && ((j == 0 && prevLabel[0] == y) || (j == 1 && prevLabel[1] == y && prevLabel[0] == yPrime)) ? 1 : 0);
                cc = 0;
                if (FAlpha[i-1][fIndexPos][yPrime] != null)
                  cc = FAlpha[i-1][fIndexPos][yPrime][kk];
                sum[kk] +=  aa * (bb + cc);
                // sum[kk] += (prevGivenCurr[i-1][y][yPrime]) * ((prevFeaturePresent && ((j == 0 && prevLabel[0] == y) || (j == 1 && prevLabel[1] == y && prevLabel[0] == yPrime)) ? 1 : 0) + FAlpha[i-1][fIndexPos][yPrime][kk]);
                if (DEBUG2)
                  System.err.printf("alpha[%d][%d][%d][%d] += % 5.3f * (%d + % 5.3f), prevLabel=%s\n", i, fIndex, y, kk, (prevGivenCurr[i-1][y][yPrime]), (prevFeaturePresent && ((j == 0 && prevLabel[0] == y) || (j == 1 && prevLabel[1] == y && prevLabel[0] == yPrime)) ? 1 : 0) , FAlpha[i-1][fIndexPos][yPrime][kk], Arrays.toString(prevLabel));
              }
            }
            if (FAlpha[i][fIndexPos] == null) {
              FAlpha[i][fIndexPos] = new double[numClasses][];
            }
            FAlpha[i][fIndexPos][y] = sum;
            if (DEBUG2)
              System.err.println("FAlpha["+i+"]["+fIndexPos+"]["+y+"] = " + Arrays.toString(sum));
            
          }
        }
      }
      if (TIMED) {
        elapsedMs = timer.stop();
        System.err.println("\t alpha took: " + Timing.toMilliSecondsString(elapsedMs) + " ms");
        timer.start();
      }
      // computing FBeta
      int docDataLen = docData.length;
      boolean nextFeaturePresent = false;
      for (int i = docDataLen-2; i >= 0; i--) {
        Set<Integer> docDataHashIPlusOne = docDataHash.get(i+1);
        // for each possible clique at this position
        for (int fIndexPos = 0; fIndexPos < activeFeatures.length; fIndexPos++) {
          fIndex = activeFeatures[fIndexPos];
          nextFeaturePresent = docDataHashIPlusOne.contains(fIndex);
          int j = map[fIndex];
          Index<CRFLabel> labelIndex = labelIndices.get(j);
          int labelIndexSize = labelIndex.size();

          if (FBeta[i+1][fIndexPos] == null) {
            FBeta[i+1][fIndexPos] = new double[numClasses][labelIndexSize];
            for (int q = 0; q < numClasses; q++)
              FBeta[i+1][fIndexPos][q] = new double[labelIndexSize]; 
          }

          for (Map.Entry<Integer, List<Integer>> entry : currNextLabelsMap.entrySet()) {
            int y = entry.getKey(); // value at i
            double[] sum = new double[labelIndexSize];
            for (int yPrime: entry.getValue()) { // value at i+1
              for (int kk=0; kk < labelIndexSize; kk++) {
                int[] nextLabel = labelIndex.get(kk).getLabel();
                // System.err.println("labelIndexSize:"+labelIndexSize+", nextGivenCurr:"+nextGivenCurr+", nextLabel:"+nextLabel+", FBeta["+(i+1)+"]["+ fIndexPos +"]["+yPrime+"] :"+FBeta[i+1][fIndexPos][yPrime]);
                aa = (nextGivenCurr[i][y][yPrime]);
                bb = (nextFeaturePresent && ((j == 0 && nextLabel[0] == yPrime) || (j == 1 && nextLabel[0] == y && nextLabel[1] == yPrime)) ? 1 : 0);
                cc = 0;
                if (FBeta[i+1][fIndexPos][yPrime] != null)
                  cc = FBeta[i+1][fIndexPos][yPrime][kk];
                sum[kk] +=  aa * ( bb + cc);
                // sum[kk] += (nextGivenCurr[i][y][yPrime]) * ( (nextFeaturePresent && ((j == 0 && nextLabel[0] == yPrime) || (j == 1 && nextLabel[0] == y && nextLabel[1] == yPrime)) ? 1 : 0) + FBeta[i+1][fIndexPos][yPrime][kk]);
                if (DEBUG2)
                  System.err.printf("beta[%d][%d][%d][%d] += % 5.3f * (%d + % 5.3f)\n", i, fIndex, y, kk, (nextGivenCurr[i][y][yPrime]), (nextFeaturePresent && ((j == 0 && nextLabel[0] == yPrime) || (j == 1 && nextLabel[0] == y && nextLabel[1] == yPrime)) ? 1 : 0), FBeta[i+1][fIndexPos][yPrime][kk]);
              }
            }
            if (FBeta[i][fIndexPos] == null) {
              FBeta[i][fIndexPos] = new double[numClasses][];
            }
            FBeta[i][fIndexPos][y] = sum;
            if (DEBUG2)
              System.err.println("FBeta["+i+"]["+fIndexPos+"]["+y+"] = " + Arrays.toString(sum));
          }
        }
      }
      if (TIMED) {
        elapsedMs = timer.stop();
        System.err.println("\t beta took: " + Timing.toMilliSecondsString(elapsedMs) + " ms");
      }
    }
    if (TIMED) {
      timer.start();
    }

    // derivative equals: VarU' * PtYYp * (1-PtYYp) + VarU * PtYYp' * (1-PtYYp) + VarU * PtYYp * (1-PtYYp)'
    // derivative equals: VarU' * PtYYp * (1-PtYYp) + VarU * PtYYp' * (1-PtYYp) + VarU * PtYYp * -PtYYp'
    // derivative equals: VarU' * PtYYp * (1-PtYYp) + VarU * PtYYp' * (1 - 2 * PtYYp)

    double deltaDivByOneMinusDelta = delta / (1.0-delta);

    Timing innerTimer = new Timing();
    long eTiming = 0;
    long dropoutTiming= 0;

    double PtYYp, PtYYpTimesOneMinusPtYYp, oneMinus2PtYYp, USum, theta, VarUp, VarU, VarUTimesOneMinus2PtYYp = 0.0;
    int fIndex, valIndex = 0;
    boolean containsFeature = false;
    // iterate over the positions in this document
    for (int i = 1; i < docData.length; i++) {
      Set<Integer> docDataHashI = docDataHash.get(i);
      Map<Integer, double[]> EForADocPosAtI = null;
      if (dropoutApprox)
        EForADocPosAtI = EForADocPos.get(i);

      // for each possible clique at this position
      for (int k = 0; k < edgeLabelIndexSize; k++) { // sum over (y, y')
        int[] label = edgeLabels[k];
        int y = label[0];
        int yP = label[1];

        if (TIMED)
          innerTimer.start();

        // important to use label as an int[] for calculating cliqueTree.prob()
        // if it's a node clique, and label index is 2, if we don't use int[]{2} but just pass 2,
        // cliqueTree is going to treat it as index of the edge clique labels, and convert 2
        // into int[]{0,2}, and return the edge prob marginal instead of node marginal
        PtYYp = cliqueTree.prob(i, label); // probability of these labels occurring in this clique with these features
        PtYYpTimesOneMinusPtYYp = PtYYp * (1.0-PtYYp);
        oneMinus2PtYYp = (1.0 - 2 * PtYYp);
        USum = 0;
        for (int jjj=0; jjj<labelIndices.size(); jjj++) {
          for (int n = 0; n < docData[i][jjj].length; n++) {
            fIndex = docData[i][jjj][n];
            if (jjj == 1)
              valIndex = k;
            else
              valIndex = yP;
            try {
            theta = weights[fIndex][valIndex];
            }catch (Exception ex) {
              System.err.printf("weights[%d][%d], map[%d]=%d, labelIndices.get(map[%d]).size() = %d, weights.length=%d\n", fIndex, valIndex, fIndex, map[fIndex], fIndex, labelIndices.get(map[fIndex]).size(), weights.length);
              throw new RuntimeException(ex);
            }

            USum += weightSquare[fIndex][valIndex];

            // first half of derivative: VarU' * PtYYp * (1-PtYYp) 
            VarUp = deltaDivByOneMinusDelta * theta;
            increScoreAllowNull(dropoutPriorGradFirstHalf, fIndex, valIndex, VarUp * PtYYpTimesOneMinusPtYYp);
            // dropoutPriorGradFirstHalf[fIndex][valIndex] += VarUp * PtYYpTimesOneMinusPtYYp;
            // dropoutPriorGrad[fIndex][k] += VarUp;
          }
        }
        
        if (TIMED) {
          eTiming += innerTimer.stop();
          innerTimer.start();
        }
        VarU = 0.5 * deltaDivByOneMinusDelta * USum;

        // double filter = (k == 3 ? 1 : 0);
        // double filter = (k == 3 ? 1 : 0);
        // double filter = k+1;

        // update function objective
        priorValue += VarU * PtYYpTimesOneMinusPtYYp;
        // priorValue += (k == 0 && j == 1 ? 1 : 0) * PtYYp;
        // priorValue += filter * PtYYp;
        // if (DEBUG2)
        //   System.err.println("priorValue += "+  filter * PtYYp);
        // priorValue += PtYYp;

        VarUTimesOneMinus2PtYYp = VarU * oneMinus2PtYYp; 

        // second half of derivative: VarU * PtYYp' * (1 - 2 * PtYYp)
        int jj = 0;
        int[] fLabel = null;
        double alpha, beta, fCount, condE, PtYYpPrime = 0;
        boolean prevFeaturePresent = false;
        boolean nextFeaturePresent = false; 
        for (int fIndexPos = 0; fIndexPos < activeFeatures.length; fIndexPos++) {
          fIndex = activeFeatures[fIndexPos];
          containsFeature = docDataHashI.contains(fIndex);

          // if (!containsFeature) continue;
          jj = map[fIndex];
          Index<CRFLabel> fLabelIndex = labelIndices.get(jj);
          for (int kk = 0; kk < fLabelIndex.size(); kk++) { // for all parameter \theta
            fLabel = fLabelIndex.get(kk).getLabel();
            // if (FAlpha[i] != null)
            //   System.err.println("fIndex: " + fIndex+", FAlpha[i].size:"+FAlpha[i].length);
            fCount = containsFeature && ((jj == 0 && fLabel[0] == yP) || (jj == 1 && k == kk)) ? 1 : 0;

            if (!dropoutApprox) {
              alpha = ((FAlpha[i][fIndexPos] == null || FAlpha[i][fIndexPos][y] == null) ? 0 : FAlpha[i][fIndexPos][y][kk]);
              beta = ((FBeta[i][fIndexPos] == null || FBeta[i][fIndexPos][yP] == null) ? 0 : FBeta[i][fIndexPos][yP][kk]);
              condE = fCount + alpha + beta;
              if (DEBUG2)
                System.err.printf("fLabel=%s, yP = %d, fCount:%f = ((jj == 0 && fLabel[0] == yP)=%b || (jj == 1 && k == kk))=%b\n", Arrays.toString(fLabel),yP, fCount,(jj == 0 && fLabel[0] == yP) , (jj == 1 && k == kk));
              PtYYpPrime = PtYYp * (condE - EForADoc.get(fIndex)[kk]);
            } else {
              double E = 0;
              if (EForADocPosAtI.containsKey(fIndex))
                E = EForADocPosAtI.get(fIndex)[kk];
              condE = fCount;
              PtYYpPrime = PtYYp * (condE - E);
            }

            if (DEBUG2)
              System.err.printf("for i=%d, k=%d, y=%d, yP=%d, fIndex=%d, kk=%d, PtYYpPrime=% 5.3f, PtYYp=% 3.3f, (condE-E[fIndex][kk])=% 3.3f, condE=% 3.3f, E[fIndex][k]=% 3.3f, alpha=% 3.3f, beta=% 3.3f, fCount=% 3.3f\n", i, k, y, yP, fIndex, kk, PtYYpPrime, PtYYp, (condE - EForADoc.get(fIndex)[kk]), condE, EForADoc.get(fIndex)[kk], alpha, beta, fCount);

            increScore(dropoutPriorGrad, fIndex, kk, VarUTimesOneMinus2PtYYp * PtYYpPrime);
            // dropoutPriorGrad[fIndex][kk] += (k == 0 && j == 1 ? 1 : 0) * PtYYpPrime;
            // dropoutPriorGrad[fIndex][kk] += filter * PtYYpPrime;
            // dropoutPriorGrad[fIndex][kk] += PtYYpPrime;
          }

          if (DEBUG2)
            System.err.println();
        }
        if (TIMED)
          dropoutTiming += innerTimer.stop();
      }
    }
    if (CONDENSE) {
      // copy for condensedFeaturesMap
      for (Map.Entry<Integer, List<Integer>> entry: condensedFeaturesMap.entrySet()) {
        int key = entry.getKey();
        List<Integer> aList = entry.getValue();
        for (int toCopyInto: aList) {
          double[] arr = dropoutPriorGrad.get(key);
          double[] targetArr = new double[arr.length];
          for (int i=0; i < arr.length; i++)
            targetArr[i] = arr[i];
          dropoutPriorGrad.put(toCopyInto, targetArr);
        }
      }
    }

    if (DEBUG3) {
      System.err.print("dropoutPriorGradFirstHalf.keys:[");
      for (int key: dropoutPriorGradFirstHalf.keySet())
        System.err.print(" "+key);
      System.err.println("]");

      System.err.print("dropoutPriorGrad.keys:[");
      for (int key: dropoutPriorGrad.keySet())
        System.err.print(" "+key);
      System.err.println("]");
    }

    double[] target, source = null;
    for (Map.Entry<Integer, double[]> entry: dropoutPriorGrad.entrySet()) {
      Integer key = entry.getKey();
      target = entry.getValue();
      if (dropoutPriorGradFirstHalf.containsKey(key)) {
        source = dropoutPriorGradFirstHalf.get(key);
        for (int i=0; i<target.length; i++) {
          // if (target == null) System.err.printf("target[%d] is null\n", i);
          // if (source == null) System.err.printf("source[%d] is null\n", i);
          target[i] += source[i];
        }
      }
    }
    // for (int i=0;i<dropoutPriorGrad.length;i++)
    //   for (int j=0; j<dropoutPriorGrad[i].length;j++) {
    //     if (DEBUG3)
    //       System.err.printf("f=%d, k=%d, dropoutPriorGradFirstHalf[%d][%d]=% 5.3f, dropoutPriorGrad[%d][%d]=% 5.3f\n", i, j, i, j, dropoutPriorGradFirstHalf[i][j], i, j, dropoutPriorGrad[i][j]);
    //     dropoutPriorGrad[i][j] += dropoutPriorGradFirstHalf[i][j];
    //   }

    if (TIMED) {
      elapsedMs = timer.stop();
      System.err.println("\t grad took: " + Timing.toMilliSecondsString(elapsedMs) + " ms");
      System.err.println("\t\t exp took: " + Timing.toMilliSecondsString(eTiming) + " ms");
      System.err.println("\t\t dropout took: " + Timing.toMilliSecondsString(dropoutTiming) + " ms");
    }

    return dropoutScale * priorValue;
  }

  /**
   * Calculates both value and partial derivatives at the point x, and save them internally.
   */
  @Override
  public void calculate(double[] x) {

    double prob = 0.0; // the log prob of the sequence given the model, which is the negation of value at this point
    final double[][] weights = to2D(x);

    if (weightSquare == null) {
      weightSquare = new double[weights.length][];
      for (int i = 0; i < weights.length; i++)
        weightSquare[i] = new double[weights[i].length];
    }
    double w = 0;
    for (int i = 0; i < weights.length; i++) {
      for (int j=0; j < weights[i].length; j++) {
        w = weights[i][j];
        weightSquare[i][j] = w * w;
      }
    }

    // the expectations over counts
    // first index is feature index, second index is of possible labeling
    double[][] E = empty2D();
    double[][] dropoutPriorGrad = empty2D();

    // takes docIndex, returns Triple<prob, E, dropoutGrad>
    ThreadsafeProcessor<Pair<Integer,Boolean>, Quadruple<Integer, Double, Map<Integer, double[]>, Map<Integer, double[]>>> threadProcessor = 
        new ThreadsafeProcessor<Pair<Integer,Boolean>, Quadruple<Integer, Double, Map<Integer, double[]>, Map<Integer, double[]>>>() {
      @Override
      public Quadruple<Integer, Double, Map<Integer, double[]>, Map<Integer, double[]>> process(Pair<Integer,Boolean> docIndexUnsup) {
        return expectedCountsAndValueForADoc(weights, docIndexUnsup.first(), false, docIndexUnsup.second());
      }
      @Override
      public ThreadsafeProcessor<Pair<Integer,Boolean>, Quadruple<Integer, Double, Map<Integer, double[]>, Map<Integer, double[]>>> newInstance() {
        return this;
      }
    };
    MulticoreWrapper<Pair<Integer,Boolean>, Quadruple<Integer, Double, Map<Integer, double[]>, Map<Integer, double[]>>> wrapper = null;
    wrapper = new MulticoreWrapper<Pair<Integer,Boolean>, Quadruple<Integer, Double, Map<Integer, double[]>, Map<Integer, double[]>>>(multiThreadGrad, threadProcessor); 

    // supervised part
    for (int m = 0; m < totalData.length; m++) {
      boolean submitIsUnsup = (m >= unsupDropoutStartIndex);
      wrapper.put(new Pair<Integer, Boolean>(m, submitIsUnsup));
      while (wrapper.peek()) {
        Quadruple<Integer, Double, Map<Integer, double[]>, Map<Integer, double[]>> result = wrapper.poll();
        int docIndex = result.first();
        boolean isUnsup = docIndex >= unsupDropoutStartIndex;
        if (isUnsup) {
          prob += unsupDropoutScale * result.second();
        } else {
          prob += result.second();
        }

        Map<Integer, double[]> partialDropout = result.fourth();
        if (partialDropout != null) {
          if (isUnsup) {
            combine2DArr(dropoutPriorGrad, partialDropout, unsupDropoutScale);
          } else {
            combine2DArr(dropoutPriorGrad, partialDropout);
          }
        }

        if (!isUnsup) {
          Map<Integer, double[]> partialE = result.third();
          if (partialE != null)
            combine2DArr(E, partialE);
        }
      }
    }
    wrapper.join();
    while (wrapper.peek()) {
      Quadruple<Integer, Double, Map<Integer, double[]>, Map<Integer, double[]>> result = wrapper.poll();
      int docIndex = result.first();
      boolean isUnsup = docIndex >= unsupDropoutStartIndex;
      if (isUnsup) {
        prob += unsupDropoutScale * result.second();
      } else {
        prob += result.second();
      }

      Map<Integer, double[]> partialDropout = result.fourth();
      if (partialDropout != null) {
        if (isUnsup) {
          combine2DArr(dropoutPriorGrad, partialDropout, unsupDropoutScale);
        } else {
          combine2DArr(dropoutPriorGrad, partialDropout);
        }
      }

      if (!isUnsup) {
        Map<Integer, double[]> partialE = result.third();
        if (partialE != null)
          combine2DArr(E, partialE);
      }
    }

    if (Double.isNaN(prob)) { // shouldn't be the case
      throw new RuntimeException("Got NaN for prob in CRFLogConditionalObjectiveFunction.calculate()" +
              " - this may well indicate numeric underflow due to overly long documents.");
    }

    // because we minimize -L(\theta)
    value = -prob;
    if (VERBOSE) {
      System.err.println("value is " + Math.exp(-value));
    }

    // compute the partial derivative for each feature by comparing expected counts to empirical counts
    int index = 0;
    for (int i = 0; i < E.length; i++) {
      for (int j = 0; j < E[i].length; j++) {
        // because we minimize -L(\theta)
        derivative[index] = (E[i][j] - Ehat[i][j]);
        if (prior == DROPOUT_PRIOR) {
          derivative[index] += dropoutScale * dropoutPriorGrad[i][j];
        }
        if (VERBOSE) {
          System.err.println("deriv(" + i + "," + j + ") = " + E[i][j] + " - " + Ehat[i][j] + " = " + derivative[index]);
        }
        index++;
      }
    }

    applyPrior(x, 1.0);
  }

  @Override
  public void calculateStochastic(double[] x, double [] v, int[] batch){
    calculateStochasticGradientLocal(x,batch);
  }

  @Override
  public int dataDimension(){
    return data.length;
  }

  private void calculateStochasticGradientLocal(double[] x, int[] batch) {

    double prob = 0.0; // the log prob of the sequence given the model, which is the negation of value at this point
    double[][] weights = to2D(x);

    double batchScale = ((double) batch.length)/((double) this.dataDimension());

    // the expectations over counts
    // first index is feature index, second index is of possible labeling
    double[][] E = empty2D();
    // iterate over all the documents
    for (int ind : batch) {
      //TODO(mengqiu) currently this doesn't taken into account gradient updates at all, need to do gradient
      prob += expectedCountsAndValueForADoc(weights, ind).second();
    }

    if (Double.isNaN(prob)) { // shouldn't be the case
      throw new RuntimeException("Got NaN for prob in CRFLogConditionalObjectiveFunction.calculate()");
    }

    value = -prob;

    // compute the partial derivative for each feature by comparing expected counts to empirical counts
    int index = 0;
    for (int i = 0; i < E.length; i++) {
      for (int j = 0; j < E[i].length; j++) {
        // real gradient should be empirical-expected;
        // but since we minimize -L(\theta), the gradient is -(empirical-expected)
        derivative[index++] = (E[i][j] - batchScale*Ehat[i][j]);
        if (VERBOSE) {
          System.err.println("deriv(" + i + "," + j + ") = " + E[i][j] + " - " + Ehat[i][j] + " = " + derivative[index - 1]);
        }
      }
    }

    applyPrior(x, batchScale);
  }

  // re-initialization is faster than Arrays.fill(arr, 0)
  private void clearUpdateEs() {
    for (int i = 0; i < eHat4Update.length; i++)
      eHat4Update[i] = new double[eHat4Update[i].length];
    for (int i = 0; i < e4Update.length; i++)
      e4Update[i] = new double[e4Update[i].length];
  }

  /**
   * Performs stochastic update of weights x (scaled by xscale) based
   * on samples indexed by batch.
   * NOTE: This function does not do regularization (regularization is done by the minimizer).
   *
   * @param x - unscaled weights
   * @param xscale - how much to scale x by when performing calculations
   * @param batch - indices of which samples to compute function over
   * @param gscale - how much to scale adjustments to x
   * @return value of function at specified x (scaled by xscale) for samples
   */
  @Override
  public double calculateStochasticUpdate(double[] x, double xscale, int[] batch, double gscale) {
    double prob = 0.0; // the log prob of the sequence given the model, which is the negation of value at this point
    // int[][] wis = getWeightIndices();
    double[][] weights = to2D(x, xscale);

    if (eHat4Update == null) {
      eHat4Update = empty2D();
      e4Update = new double[eHat4Update.length][];
      for (int i = 0; i < e4Update.length; i++)
        e4Update[i] = new double[eHat4Update[i].length];
    } else {
      clearUpdateEs();
    }

    // Adjust weight by -gscale*gradient
    // gradient is expected count - empirical count
    // so we adjust by + gscale(empirical count - expected count)

    // iterate over all the documents
    for (int ind : batch) {
      // clearUpdateEs();

      empiricalCountsForADoc(eHat4Update, ind);
      // TOOD(mengqiu) this is broken right now
      prob += expectedCountsAndValueForADoc(weights, ind).second();

      /* the commented out code below is to iterate over the batch docs instead of iterating over all
         parameters at the end, which is more efficient; but it would also require us to clearUpdateEs()
         for each document, which is likely to out-weight the cost of iterating over params once at the end

      for (int i = 0; i < data[ind].length; i++) {
        // for each possible clique at this position
        for (int j = 0; j < data[ind][i].length; j++) {
          Index labelIndex = labelIndices.get(j);
          // for each possible labeling for that clique
          for (int k = 0; k < labelIndex.size(); k++) {
            for (int n = 0; n < data[ind][i][j].length; n++) {
              // Adjust weight by (eHat-e)*gscale (empirical count minus expected count scaled)
              int fIndex = docData[i][j][n];
              x[wis[fIndex][k]] += (eHat4Update[fIndex][k] - e4Update[fIndex][k]) * gscale;
            }
          }
        }
      }
      */
    }

    if (Double.isNaN(prob)) { // shouldn't be the case
      throw new RuntimeException("Got NaN for prob in CRFLogConditionalObjectiveFunction.calculate()");
    }

    value = -prob;

    int index = 0;
    for (int i = 0; i < e4Update.length; i++) {
      for (int j = 0; j < e4Update[i].length; j++) {
        // real gradient should be empirical-expected;
        // but since we minimize -L(\theta), the gradient is -(empirical-expected)
        // the update to x(t) = x(t-1) - g(t), and therefore is --(empirical-expected) = (empirical-expected)
        x[index++] += (eHat4Update[i][j] - e4Update[i][j]) * gscale;
      }
    }

    return value;
  }

  /**
   * Performs stochastic gradient update based
   * on samples indexed by batch, but does not apply regularization.
   *
   * @param x - unscaled weights
   * @param batch - indices of which samples to compute function over
   */
  @Override
  public void calculateStochasticGradient(double[] x, int[] batch) {
    if (derivative == null) {
      derivative = new double[domainDimension()];
    }
    // int[][] wis = getWeightIndices();
    // was: double[][] weights = to2D(x, 1.0); // but 1.0 should be the same as omitting 2nd parameter....
    double[][] weights = to2D(x);

    if (eHat4Update == null) {
      eHat4Update = empty2D();
      e4Update = new double[eHat4Update.length][];
      for (int i = 0; i < e4Update.length; i++)
        e4Update[i] = new double[eHat4Update[i].length];
    } else {
      clearUpdateEs();
    }

    // Adjust weight by -gscale*gradient
    // gradient is expected count - empirical count
    // so we adjust by + gscale(empirical count - expected count)

    // iterate over all the documents
    for (int ind : batch) {
      // clearUpdateEs();

      empiricalCountsForADoc(eHat4Update, ind);
      // TODO(mengqiu) broken, does not do E calculation
      expectedCountsForADoc(weights, ind);

      /* the commented out code below is to iterate over the batch docs instead of iterating over all
         parameters at the end, which is more efficient; but it would also require us to clearUpdateEs()
         for each document, which is likely to out-weight the cost of iterating over params once at the end

      for (int i = 0; i < data[ind].length; i++) {
        // for each possible clique at this position
        for (int j = 0; j < data[ind][i].length; j++) {
          Index labelIndex = labelIndices.get(j);
          // for each possible labeling for that clique
          for (int k = 0; k < labelIndex.size(); k++) {
            for (int n = 0; n < data[ind][i][j].length; n++) {
              // Adjust weight by (eHat-e)*gscale (empirical count minus expected count scaled)
              int fIndex = docData[i][j][n];
              x[wis[fIndex][k]] += (eHat4Update[fIndex][k] - e4Update[fIndex][k]) * gscale;
            }
          }
        }
      }
      */
    }

    int index = 0;
    for (int i = 0; i < e4Update.length; i++) {
      for (int j = 0; j < e4Update[i].length; j++) {
        // real gradient should be empirical-expected;
        // but since we minimize -L(\theta), the gradient is -(empirical-expected)
        // the update to x(t) = x(t-1) - g(t), and therefore is --(empirical-expected) = (empirical-expected)
        derivative[index++] = (-eHat4Update[i][j] + e4Update[i][j]);
      }
    }
  }

  /**
   * Computes value of function for specified value of x (scaled by xscale)
   * only over samples indexed by batch.
   * NOTE: This function does not do regularization (regularization is done by the minimizer).
   *
   * @param x - unscaled weights
   * @param xscale - how much to scale x by when performing calculations
   * @param batch - indices of which samples to compute function over
   * @return value of function at specified x (scaled by xscale) for samples
   */
  @Override
  public double valueAt(double[] x, double xscale, int[] batch) {
    double prob = 0.0; // the log prob of the sequence given the model, which is the negation of value at this point
    // int[][] wis = getWeightIndices();
    double[][] weights = to2D(x, xscale);

    // iterate over all the documents
    for (int ind : batch) {
      prob += valueForADoc(weights, ind);
    }

    if (Double.isNaN(prob)) { // shouldn't be the case
      throw new RuntimeException("Got NaN for prob in CRFLogConditionalObjectiveFunction.calculate()");
    }

    value = -prob;
    return value;
  }

  @Override
  public int[][] getFeatureGrouping() {
    if (featureGrouping != null)
      return featureGrouping;
    else {
      int[][] fg = new int[1][];
      fg[0] = ArrayMath.range(0, domainDimension());
      return fg;
    }
  }

  public void setFeatureGrouping(int[][] fg) {
    this.featureGrouping = fg;
  }

  private void applyPrior(double[] x, double batchScale) {
    // incorporate priors
    if (prior == QUADRATIC_PRIOR) {
      double sigmaSq = sigma * sigma;
      double lambda = 1 / 2.0 / sigmaSq;
      for (int i = 0; i < x.length; i++) {
        double w = x[i];
        value += batchScale * w * w * lambda;
        derivative[i] += batchScale * w / sigmaSq;
      }
    } else if (prior == HUBER_PRIOR) {
      double sigmaSq = sigma * sigma;
      for (int i = 0; i < x.length; i++) {
        double w = x[i];
        double wabs = Math.abs(w);
        if (wabs < epsilon) {
          value += batchScale*w * w / 2.0 / epsilon / sigmaSq;
          derivative[i] += batchScale*w / epsilon / sigmaSq;
        } else {
          value += batchScale*(wabs - epsilon / 2) / sigmaSq;
          derivative[i] += batchScale*((w < 0.0) ? -1.0 : 1.0) / sigmaSq;
        }
      }
    } else if (prior == QUARTIC_PRIOR) {
      double sigmaQu = sigma * sigma * sigma * sigma;
      double lambda = 1 / 2.0 / sigmaQu;
      for (int i = 0; i < x.length; i++) {
        double w = x[i];
        value += batchScale * w * w * w * w * lambda;
        derivative[i] += batchScale * w / sigmaQu;
      }
    }
  }
}
