/*
 * Created on Aug 7, 2009
 *
 * TODO To change the template for this generated file go to
 * Window - Preferences - Java - Code Style - Code Templates
 */
package edu.tum.cs.logic.sat.weighted;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;
import java.util.Vector;
import java.util.Map.Entry;

import salvo.jesus.util.Collections;

import edu.tum.cs.inference.IParameterHandler;
import edu.tum.cs.inference.ParameterHandler;
import edu.tum.cs.logic.Atom;
import edu.tum.cs.logic.Formula;
import edu.tum.cs.logic.GroundAtom;
import edu.tum.cs.logic.GroundLiteral;
import edu.tum.cs.logic.GroundedFormula;
import edu.tum.cs.logic.Negation;
import edu.tum.cs.logic.PossibleWorld;
import edu.tum.cs.logic.WorldVariables;
import edu.tum.cs.logic.sat.SampleSAT;
import edu.tum.cs.srl.Database;
import edu.tum.cs.util.datastruct.Pair;

/**
 * MC-SAT inference algorithm (Poon and Domingos 2006) 
 * @author jain
 */
public class MCSAT implements IParameterHandler {

	protected WeightedClausalKB kb;
	protected WorldVariables vars;
	protected Database db; 
	protected Random rand;
	protected GroundAtomDistribution dist;
	protected boolean verbose = false, debug = false;
	protected int infoInterval = 100;
	protected ParameterHandler paramHandler;
	protected SampleSAT sat;
	protected Vector<SoftEvidence> softEvidence;
	
	public class SoftEvidence {
		public WeightedClause wc;
		public double p;
		public double count;
		public SoftEvidence(WeightedClause wc, double p) {
			count = 0;
			this.wc = wc;
			this.p = p;
		}
	}
	
	public MCSAT(WeightedClausalKB kb, WorldVariables vars, Database db) throws Exception {		
		this.kb = kb;
		this.vars = vars;
		this.db = db;
		this.rand = new Random();
		this.dist = new GroundAtomDistribution(vars);
		this.paramHandler = new ParameterHandler(this);
		this.softEvidence = new Vector<SoftEvidence>();
		PossibleWorld state = new PossibleWorld(vars);
		sat = new SampleSAT(state, vars, db.getEntries());				
		paramHandler.addSubhandler(sat.getParameterHandler());
		paramHandler.add("infoInterval", "setInfoInterval");
		paramHandler.add("verbose", "setVerbose");
	}	
	
	public WeightedClausalKB getKB() {
		return kb;
	}
	
	public void setVerbose(boolean verbose) {
		this.verbose = verbose;
	}
	
	public void setDebugMode(boolean active) {
		this.debug = active;
	}
	
	public void setInfoInterval(int interval) {
		this.infoInterval = interval;
	}

	public GroundAtomDistribution run(int steps) throws Exception {
		if(debug) {
			System.out.println("\nMC-SAT constraints:");
			for(WeightedClause wc : kb)
				System.out.println("  " + wc);
			System.out.println();
		}
		verbose = verbose || debug;
		if(verbose) 
			System.out.printf("%s sampling (%d weighted formulas)...\n", this.getAlgorithmName(), this.kb.size());		
		
		// find initial state satisfying all hard constraints
		if(verbose) System.out.println("finding initial state...");
		Vector<WeightedClause> M = new Vector<WeightedClause>();
		for(Entry<WeightedFormula, Vector<WeightedClause>> e : kb.getFormulasAndClauses()) {
			WeightedFormula wf = e.getKey();
			if(wf.isHard) {
				M.addAll(e.getValue());
			}
		}		
		sat.setDebugMode(debug);
		sat.initConstraints(M);
		sat.run();
		
		// actual MC-SAT sampling
		for(int i = 0; i < steps; i++) {
			
			M.clear();
			
			for(Entry<WeightedFormula, Vector<WeightedClause>> e : kb.getFormulasAndClauses()) {
				WeightedFormula wf = e.getKey();
				if(wf.formula.isTrue(sat.getState())){
					boolean satisfy = wf.isHard || rand.nextDouble() * Math.exp(wf.weight) > 1.0;
					if(satisfy)
						M.addAll(e.getValue());					
				}				
			}
			
			// soft evidence clauses
			if(i > 0)
				for(SoftEvidence se : this.softEvidence) {
					if(se.wc.isTrue(sat.getState())) {
						se.count += 1;
						if(se.count/i < se.p)
							M.add(se.wc);							
					}
				}			
			
			if(verbose && (i+1) % infoInterval == 0) {
				System.out.printf("MC-SAT step %d: %d constraints to be satisfied\n", i+1, M.size());
				if(debug) {
					for(WeightedClause wc : M)
						System.out.println("    " + wc);
				}
			}
			sat.initConstraints(M);
			sat.run();
	
			if(false) {
				sat.getState().print();
			}
			
			synchronized(dist) {
				dist.addSample(sat.getState(), 1.0);
			}
		}
		synchronized(dist) {
			dist.normalize();
		}
		
		return dist;
	}
	
	public void setP(double p) {
		sat.setPSampleSAT(p);
	}
	
	public static class GroundAtomDistribution implements Cloneable {
		public double[] sums;
		public double Z;
		public int numSamples;
		
		public GroundAtomDistribution(WorldVariables vars){
			this.Z = 0.0;
			this.numSamples = 0;
			this.sums = new double[vars.size()];
		}
		
		public void addSample(PossibleWorld w, double weight){
			for(GroundAtom ga : w.getVariables()){
				if(w.isTrue(ga)){
					sums[ga.index] += weight;
				}
			}
			Z += weight;
			numSamples++;
		}
		
		public void normalize(){
			if(Z != 1.0) {
				for(int i = 0; i < sums.length; i++){
					sums[i] /= Z;
				}
				Z = 1.0;
			}
		}
		
		public double getResult(int indx){
			return sums[indx];
		}
		
		public GroundAtomDistribution clone() throws CloneNotSupportedException {
			return (GroundAtomDistribution)super.clone();
		}
	}

	public double getResult(GroundAtom ga) {
		return dist.getResult(ga.index);
	}
	
	public GroundAtomDistribution pollResults() throws CloneNotSupportedException {
		GroundAtomDistribution ret = null;
		synchronized(dist) {
			ret = this.dist.clone();
		}
		return ret;
	}

	public ParameterHandler getParameterHandler() {
		return paramHandler;
	}
	
	public String getAlgorithmName() {
		return String.format("%s[%s]", this.getClass().getSimpleName(), sat.getAlgorithmName());
	}
	
	public void addSoftEvidence(Atom f, Double p) throws Exception {
		Formula ga = f.ground(new HashMap<String,String>(), vars, null);
		Formula nga = new Negation(ga).toCNF();		
		this.softEvidence.add(new SoftEvidence(new WeightedClause(ga, 0.0, false), p));
		this.softEvidence.add(new SoftEvidence(new WeightedClause(nga, 0.0, false), 1.0-p));
	}
}
