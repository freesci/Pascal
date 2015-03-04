package ch.unil.genescore.vegas;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import no.uib.cipr.matrix.SymmDenseEVD;
import no.uib.cipr.matrix.UpperSymmDenseMatrix;
import ch.unil.genescore.gene.Gene;
import ch.unil.genescore.gene.GenomicElement;
import ch.unil.genescore.main.Main;
import ch.unil.genescore.main.Settings;
import ch.unil.genescore.main.Utils;


/**
 * Summarize p-values of multiple snps at a locus
 */
public class GenomeWideScoring {


	/** Appended to output files right before the file extension */
	private String additionalOutputFileSuffix_ = "";
	
	/** The loaded genes (all genes if genesToBeLoaded_ is empty) */
	protected Collection<Gene> genes_ = null;	
	
	/** The reference population instance, used to load snp positions and genotypes */
	protected ReferencePopulation refpop_ = null;
		
	
	/**  for each gene contains  relevant snp_ids and weights used in Projection matrix*/
	//private  HashMap<String, SnpWeightMap> snpWeightsForEachGene_ = null;
	/**  for each gene contains  relevant snp_ids and weights used in Projection matrix*/
	private  AccessGenewiseSnpWeights snpWeightsForEachGene_ = null;
	
	/** The genes for which the number of snps was above the limit */	
	private GeneResultsSnpsOutOfBounds GeneResultsSnpsOutOfBounds_ =  null;
	
	/** The genes for which no score could be computed */	
	protected GeneResultsNoScore noScores_ = null;
	private  GeneResultsScore scores_ = null;
	
	
	/** Flag set false when computeScore() has been run first time (to display info only on first run) */
	private boolean firstRun_ = true;		
	protected double[] fakeSignal_ = null;
	// ============================================================================
	// PUBLIC METHODS
	
	/** Constructor */
	public GenomeWideScoring() {
		
	
		GeneResultsSnpsOutOfBounds_ =  new GeneResultsSnpsOutOfBounds();
		noScores_ = new GeneResultsNoScore();
		if (Settings.weightFileFormat_=="genewise"){
			snpWeightsForEachGene_ = new GenewiseSnpWeights();
		}				
		if(Settings.useFakeSignal_){
			ReferencePopulationFakeSignal fakeSignalGenerator = new ReferencePopulationFakeSignal();
			fakeSignalGenerator.runFakeSignal();
			fakeSignal_ = fakeSignalGenerator.getSignal();
		}
	}


	// ----------------------------------------------------------------------------

	

	private class ChromosomeSwitchChecker {
		
		String prevChrom_ = "not initialized";				
		public void check(GenomicElement el, ReferencePopulation refpop){
			if (!el.chr_.equals(prevChrom_)){
				
				if (Settings.verbose_) {
					Main.println();
					Main.println("Chromosome " + el.chr_);
					Main.println("----------------");
				} else {
					Main.println("- " + el.chr_ + " ...");
				}
				// Initialize refpop
				refpop.initialize(el.chr_);
				if (Settings.verbose_)
					Main.println();

				
			}
			prevChrom_ = el.chr_;						
		}
	}

	
	/** 
	 * Compute scores for the given list of genes, which must be ordered by chromosome and starting position.
	 * Gene scores are saved in the gene instances (Gene.score_).
	 * Results are written to file if the flag is set.
	 */
	
	public void computeScores() {

		boolean writeFile=true;
		//genes_ = genes;
		if (genes_ == null || genes_.size() == 0) {
			Main.warning("GenomeWideScoring.computeScores(): No genes specified");
			return;
		}
		
		GeneResultsSnpsOutOfBounds_ =  new GeneResultsSnpsOutOfBounds();
		
		if (firstRun_) {
			// Print info on method and parameters used to console
			printConsoleMethodInfo();
			// Load MTJ, display info
			if (Settings.writeDetailedErrorOutput_){
				initializeMtj();
			}
		}
		// Open output file
		scores_ =  new GeneResultsScore();						
		if (writeFile) {			
			scores_.setExporter(additionalOutputFileSuffix_);						
		}

		// Print header for results displayed on console
		if (Settings.verbose_)
			printConsoleHeader();
		else
			Main.println("Computing gene scores for chromosome:");
				// Compute score for each gene
		ChromosomeSwitchChecker switchChecker = new ChromosomeSwitchChecker();
		for (Gene gene : genes_) {
					
			switchChecker.check(gene, refpop_);
			GeneScoreEvaluator evaluator = null;
			evaluator = computeScore(gene);
			// Evaluator is only null if the gene should be skipped (zero snps or exceeding max number of snps)
			if (evaluator == null)
				continue;			
			scores_.writeLine(evaluator, gene);
		}
		scores_.getExporter().close();				
		GeneResultsSnpsOutOfBounds_.writeResultsToFile(additionalOutputFileSuffix_);	
		noScores_.writeResultsToFile(additionalOutputFileSuffix_);		
		firstRun_ = false;
	}

	
	// ----------------------------------------------------------------------------
	private GeneData setupGeneData(GeneWithItsSnps gene){
		GeneData currentGeneData = null;
		if (Settings.useProjectionVegas_){
			
			if (!Settings.withZScore_)
				throw new RuntimeException("Projection-vegas not implemented without z-scores");
			String currentGeneId= gene.getGeneId();
			LinkedList<Snp> currentSnpsWithGenotypes=refpop_.getSnpsWithGenotypes();
			SnpWeightPairs SnpsAndWeights = snpWeightsForEachGene_.getOverlappedSnpsWithWeights(currentGeneId,currentSnpsWithGenotypes);
			
			if (SnpsAndWeights==null)
				return null;
				currentGeneData = new GeneDataProjection(gene.getSnpList(), currentSnpsWithGenotypes,SnpsAndWeights);			
			}
			else if (Settings.useFakePhenotype_){				
				currentGeneData = new GeneDataFakePhenotype(gene.getSnpList(), fakeSignal_);
			}
			else{
				currentGeneData = new GeneData(gene.getSnpList());			
			}
		return(currentGeneData);
		
	}	
		
	
	/** Compute score for the given gene */
	protected GeneScoreEvaluator computeScore(Gene gene) {				
		
	
		// Get the snps that are in the window around the given gene			
		GeneWithItsSnps geneAndSnps = new GeneWithItsSnps(gene,refpop_.findSnps(gene));
					
		if (Settings.verbose_) {
			 geneAndSnps.printGeneNameAndNrOfSnps();			
		}
		refpop_.updateLoadedGenotypes(gene);				
		removeLowMafSnps(geneAndSnps.getSnpList());				
		if (!geneAndSnps.checkNrOfSnps(GeneResultsSnpsOutOfBounds_)){
			return null;
		}		
		GeneData currentGeneData = setupGeneData(geneAndSnps);			
		currentGeneData.processData();	
			
									
		if (!Settings.writeGenewiseSnpFiles_.equals(""))	{		
      	  String fileNameStr="snpVals_gene" +"_"+ gene.symbol_ + "_" + gene.id_ + ".txt";
      	  currentGeneData.writeGeneSnpsToFile(fileNameStr);
        }		
		
		 if (!Settings.writeCorFiles_.equals("")){
			 String fileNameString="corMat_snpVals_gene" + "_" + gene.symbol_ +"_"+ gene.getId() + ".txt";
       	  currentGeneData.writeCovMatToFile(fileNameString);
		// Computes gene scores		
		 }		 
		return calculateScore(currentGeneData, gene);
	}
	
	public GeneScoreEvaluator calculateScore(GeneData currentGeneData, Gene gene){
		
		GeneScoreEvaluator evaluator = Settings.getGeneScoreEvaluator();
        evaluator.setDataFromGeneData(currentGeneData);
		// Compute gene score
		long t0 = System.currentTimeMillis();
		boolean success = evaluator.computeScore();
		long t1 = System.currentTimeMillis();
		
		// Set gene score
		gene.setScore(evaluator.getScore());
		gene.calcChi2StatFromScore();

		// Print info to console
		if (Settings.verbose_) {
			Main.print("\t" + Utils.padRight(Utils.chronometer(t1 - t0), 22));
			if (evaluator != null)
				Main.print(evaluator.getConsoleOutput());
			Main.println();
		}

		// If the score could not be computed (exception in analytic vegas)
		if (!success) {
			String str = gene.toString();
			str += "\t" + evaluator.getNoScoreOutput();
			noScores_.add(str);			
			if (Settings.verbose_)
				Main.println(str);
		}

		return evaluator;
	}
	
	// ----------------------------------------------------------------------------

	/** Print info to console about relevant parameters and which gene scoring method is used */
	private void printConsoleMethodInfo() {
		
		// Print info
		if (Settings.useAnalyticVegas_) {
			Main.println("- Gene scoring method: analytic VEGAS");
			if (Settings.snpWeightingDelta_.get(0) != 0.0 || Settings.snpWeightingDelta_.size() > 1)
				Main.println("- SNP weighting with delta: " + Utils.array2string(Settings.snpWeightingDelta_, ","));

		} else if (Settings.useSimulationVegas_) {
			Main.println("- Gene scoring method: original VEGAS using MC simulation (not recommended)");
			if (Settings.testStatisticNumSnps_ > 0)
				Main.println("- Test statistic: using up to " + Settings.testStatisticNumSnps_ + " SNPs within gene windows");
			else
				Main.println("- Test statistic: using all SNPs within gene windows");
		
		} else if (Settings.useOrthoOrder_) {
			Main.println("TBD print ortho order params");
		
		}
        else if (Settings.useOrthoSum_) {
            Main.println("TBD print ortho sum params");
        }
        else if (Settings.useProjectionVegas_) {
            Main.println("TBD print projection vegas params");
        }  
        else if (Settings.useMaxVegas_) {
            Main.println("TBD print max vegas params");
        } 
        else if (Settings.useMaxEffVegas_) {
            Main.println("TBD print maxeff vegas params");
        } 
        else if (Settings.runEqtlAnalysis_) {
            Main.println("TBD print max vegas params");
        } 
        else {
			throw new RuntimeException("No gene scoring method selected");
		}
	}
	
	
	// ----------------------------------------------------------------------------

	/** Print a header for the gene score computation to the console */
	private void printConsoleHeader() {

		if (Settings.useAnalyticVegas_)
			Main.println("Format: gene_id, symbol, #snps, runtime, [warnings]");
		else if (Settings.useSimulationVegas_)
			Main.println("Format: gene_id, symbol, #snps, #simulations, runtime, [warnings]");
		else if (Settings.useOrthoOrder_)
			Main.println("TBD");
        else if (Settings.useOrthoSum_)
            Main.println("TBD");
        else if (Settings.useProjectionVegas_)
            Main.println("TBD");
        else if (Settings.useMaxVegas_)
            Main.println("TBD");
        else if (Settings.useMaxEffVegas_) {
            Main.println("TBD");
        } 
        else if (Settings.runEqtlAnalysis_)
            Main.println("TBD");
		else
			throw new RuntimeException("No gene scoring method selected");

		Main.println("------------------------------------------------------------------------------");
	}
	
	
	

	// ----------------------------------------------------------------------------

	
	
	

	/** Load MTJ and display info */
	private void initializeMtj() {
		// This is just some bogus operation to load MTJ jni so that potential warning msgs are displayed here
		// TODO is there a way to check if the native libs could be loaded and display a more understandable msg for the user?		
		ConsoleHandler handler = new ConsoleHandler();				
			
			handler.setLevel(Level.FINEST);		
			Logger.getLogger("com.github.fommil.jni.JniLoader").addHandler(handler);
			Logger log =Logger.getLogger("com.github.fommil.jni.JniLoader");
		//		log.addHandler(handler);
			log.setLevel(Level.FINEST);
		
		
		Main.println("- Attempting to load native matrix library");
		try {
			SymmDenseEVD.factorize(new UpperSymmDenseMatrix(1));
		} catch (Exception e) { }
		Main.println();
		//handler.setLevel(Level.SEVERE);
		//Do not display further info from MTJ		
		//Logger.getLogger("com.github.fommil.jni.JniLoader").setLevel(Level.WARNING);		
		//Logger.getLogger("com.github.fommil.jni.JniLoader").setLevel(Level.FINEST);
	}
	
	public void removeLowMafSnps(Collection<Snp> snpList){
		
		Iterator<Snp> it = snpList.iterator();
		while (it.hasNext()){
			Snp snp = it.next();
			if (snp.getMaf() < Settings.useMafCutoff_ || snp.getAlleleSd()==0)
				it.remove();
		}
		
	}
	// ============================================================================
	// GETTERS AND SETTERS
	public void setAdditionalOutputFileSuffix(String ext) { additionalOutputFileSuffix_ = ext; }
	
	//public Genome getSnps() { return snps_; }
	public Collection<Gene> getGenes() { return genes_; }
	//public  void setGenes(List<Gene> genes) { genes_ = genes; }
	public  void setGenes(Collection<Gene> genes) { genes_ = genes;	}
	public  void setReferencePopulation(ReferencePopulation refpop) { refpop_=refpop;}

}