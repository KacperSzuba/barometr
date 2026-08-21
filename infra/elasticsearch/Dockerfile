# Elasticsearch with the Polish analyser, which the stock image does not carry.
#
# `analysis-stempel` is what makes a query for "ustawy o cenach energii" find an act
# titled "ustawa o cenach energii": Polish inflects everything, and an index built on
# a generic analyser matches the nominative or nothing. The plugin ships as a separate
# download from Elastic, so the image has to be built rather than pulled — which is
# also why the version is pinned here and in the version catalog together: the plugin
# is published per Elasticsearch release and refuses to install into another one.
FROM docker.elastic.co/elasticsearch/elasticsearch:9.5.1

RUN elasticsearch-plugin install --batch analysis-stempel
