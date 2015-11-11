package de.unirostock.sems.masymos.query.types;


import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.Version;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexHits;

import de.unirostock.sems.masymos.analyzer.AnnotationIndexAnalyzer;
import de.unirostock.sems.masymos.configuration.NodeLabel;
import de.unirostock.sems.masymos.configuration.Property;
import de.unirostock.sems.masymos.database.Manager;
import de.unirostock.sems.masymos.database.traverse.DBModelTraverser;
import de.unirostock.sems.masymos.query.IQueryInterface;
import de.unirostock.sems.masymos.query.enumerator.AnnotationFieldEnumerator;
import de.unirostock.sems.masymos.query.results.AnnotationResultSet;
import de.unirostock.sems.masymos.query.results.ModelResultSet;

public class AnnotationQuery implements IQueryInterface {
	private final Analyzer analyzer = AnnotationIndexAnalyzer.getAnnotationIndexAnalyzer();
	private final Index<Node> index = Manager.instance().getAnnotationIndex();
	private final String[] indexedFields = {	Property.General.URI, 
												Property.General.NONRDF, 
												Property.General.RESOURCETEXT};
	private Map<AnnotationFieldEnumerator, List<String>> queryMap =  new HashMap<AnnotationFieldEnumerator, List<String>>();
	
	
	private float threshold = -1f;
	private int bestN = Integer.MAX_VALUE;


	public float getThreshold() {
		return threshold;
	}

	public void setThreshold(float threshold) {
		this.threshold = threshold;
	}

	public int getBestN() {
		return bestN;
	}

	public void setBestN(int bestN) {
		this.bestN = bestN;
	}

	
	@Override
	public Analyzer getAnalyzer() {
		return analyzer;
	}

	@Override
	public Index<Node> getIndex() {
		return index;
	}

	@Override
	public String[] getIndexedFields() {
		return indexedFields;
	}

	@Override
	public Query getQuery() {
		Query q = null;
		try {
			q = createQueryFromQueryMap();
		} catch (ParseException e) {
			//TODO log me
			q = null;
		}
		return q;
	}	

	@Override
	public AnnotationFieldEnumerator[] getIndexFields() {
		return AnnotationFieldEnumerator.class.getEnumConstants();
	}
	
	@Override
	public List<ModelResultSet> getModelResults() {
		return retrieveModelResultsByAnnotation();
	}
	
	public List<AnnotationResultSet> getResults(){
		return retrieveAnnotionResults();
	}
	
	public void addQueryClause(AnnotationFieldEnumerator field, String queryString) {
		
		if (AnnotationFieldEnumerator.NONE.equals(field)){
			//if a NONE field was provided skip the list and expand to all
			queryMap = new HashMap<AnnotationFieldEnumerator, List<String>>();
			AnnotationFieldEnumerator[] pe = getIndexFields();
			for (int i = 0; i < pe.length; i++) {
				AnnotationFieldEnumerator e = pe[i];
				if (e.equals(AnnotationFieldEnumerator.NONE)) continue;
				List<String> termList = new LinkedList<String>();
				termList.add(queryString);
				queryMap.put(e, termList);
			}
		} else {
			//add single field -> string pair
			List<String> termList = null; 
			if (queryMap.keySet().contains(field)){
				termList = queryMap.get(field);
			} else {
				termList = new LinkedList<String>();
			}
			termList.add(queryString);
			
			queryMap.put(field, termList);
		}
	}
	
	private Query createQueryFromQueryMap() throws ParseException{
		if (queryMap.isEmpty()) return null;
		
		QueryParser qp = new QueryParser(Version.LUCENE_35, Property.General.RESOURCETEXT, analyzer);
		StringBuffer q = new StringBuffer();
		for (Iterator<AnnotationFieldEnumerator> queryMapIt = queryMap.keySet().iterator(); queryMapIt.hasNext();) {
			AnnotationFieldEnumerator pe = (AnnotationFieldEnumerator) queryMapIt.next();
			List<String> termList = queryMap.get(pe);
			for (Iterator<String> termListIt = termList.iterator(); termListIt.hasNext();) {
				String term = (String) termListIt.next();
				if (StringUtils.isEmpty(term)) continue;
				try {
					//try to parse term
					qp.parse(term);
				} catch (ParseException e) {
					//if it fails, escape term
					term = QueryParser.escape(term);
				}				
				q.append(pe.getElementNameEquivalent());
				q.append(":(");
				q.append(term);
				q.append(")^");
				q.append(pe.getElementWeightEquivalent());
				q.append(" ");
			}
		}						 
		return qp.parse(q.toString());		
	}
	
	private List<ModelResultSet> retrieveModelResultsByAnnotation(){

		IndexHits<Node> hits = retrieveHits();
		
		if ((hits == null) || (hits.size() == 0)) {
			return new LinkedList<ModelResultSet>();
		}
		List<ModelResultSet> resultList = new LinkedList<ModelResultSet>();
		
		int resultCount = 1;
		for (Iterator<Node> hitsIt = hits.iterator(); hitsIt.hasNext();) {
			Node node = (Node) hitsIt.next();
			float score = hits.currentScore();
			//end loop if bestN or threshold are met
			if ((resultCount >= bestN) || (score < threshold)) break;
			if (node.hasLabel(NodeLabel.Types.RESOURCE)){
				resultList.addAll(DBModelTraverser.getModelResultSetFromNode(node, score, "AnnotationIndex"));				
			}
			resultCount++;
		}
		return resultList;
	}
	
	private List<AnnotationResultSet> retrieveAnnotionResults(){
		
		IndexHits<Node> hits = retrieveHits();
		if ((hits == null) || (hits.size() == 0)) {
			return new LinkedList<AnnotationResultSet>();
		}
		List<AnnotationResultSet> resultList = new LinkedList<AnnotationResultSet>();
		
		int resultCount = 1;		
		for (Iterator<Node> hitsIt = hits.iterator(); hitsIt.hasNext();) {
			Node node = (Node) hitsIt.next();
			float score = hits.currentScore();
			//end loop if bestN or threshold are met
			if ((resultCount >= bestN) || (score < threshold)) break;
			if (node.hasLabel(NodeLabel.Types.RESOURCE)){
				AnnotationResultSet ars = new AnnotationResultSet(score, (String)node.getProperty(Property.General.URI, ""));
				List<Node> documentNodes = DBModelTraverser.getDocumentsFromNode(node);
				for (Iterator<Node> docNodeIt = documentNodes.iterator(); docNodeIt.hasNext();) {
					Node docNode = (Node) docNodeIt.next();
					if (docNode.hasProperty(Property.General.URI)) ars.addRelatedModelURI((String)docNode.getProperty(Property.General.URI));
				}
				resultList.add(ars);				
			}
			resultCount++;
		}		
		return resultList;
	}


	private IndexHits<Node> retrieveHits(){
		if (queryMap.isEmpty()) return null;
		
		Query q = null;
		try {
			q = createQueryFromQueryMap();
		} catch (ParseException e) {
			// TODO log me
			return null;
		}
		
		return index.query(q);
	}

}