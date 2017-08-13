(function () {
    'use strict';
    angular.module('theHiveControllers').controller('CaseObservablesItemCtrl',
        function ($scope, $state, $stateParams, $q, $timeout, $document, CaseTabsSrv, CaseArtifactSrv, CortexSrv, PSearchSrv, AnalyzerSrv, NotificationSrv, VersionSrv, appConfig) {
            var observableId = $stateParams.itemId,
                observableName = 'observable-' + observableId;

            $scope.caseId = $stateParams.caseId;
            $scope.report = null;
            $scope.analyzers = {};
            $scope.analyzerJobs = {};
            $scope.jobs = {};
            $scope.state = {
                'editing': false,
                'isCollapsed': false,
                'dropdownOpen': false,
                'logMissing': ''
            };

            $scope.artifact = {};
            $scope.artifact.tlp = $scope.artifact.tlp || -1;
            $scope.analysisEnabled = VersionSrv.hasCortex();
            $scope.protectDownloadsWith = appConfig.config.protectDownloadsWith;

            $scope.editorOptions = {
                lineNumbers: true,
                theme: 'twilight',
                readOnly: 'nocursor',
                lineWrapping: true,
                mode: 'vb'
            };

            CaseArtifactSrv.api().get({
                'artifactId': observableId
            }, function (observable) {

                // Add tab
                CaseTabsSrv.addTab(observableName, {
                    name: observableName,
                    label: observable.data || observable.attachment.name,
                    closable: true,
                    state: 'app.case.observables-item',
                    params: {
                        itemId: observable.id
                    }
                });

                // Select tab
                $timeout(function() {
                    CaseTabsSrv.activateTab(observableName);
                }, 0);


                // Prepare the scope data
                $scope.initScope(observable);

            }, function (response) {
                NotificationSrv.error('artifactDetails', response.data, response.status);
                CaseTabsSrv.activateTab('observables');
            });

            $scope.initScope = function (artifact) {
                $scope.artifact = artifact;

                // Get analyzers available for the observable's datatype
                AnalyzerSrv.forDataType(artifact.dataType)
                    .then(function (analyzers) {
                        $scope.analyzers = analyzers;
                    }, function () {
                        $scope.analyzers = [];
                    })
                    .finally(function () {
                        $scope.jobs = CortexSrv.list($scope, $scope.caseId, observableId, $scope.onJobsChange);
                    });

            };

            $scope.onJobsChange = function (updates) {
                $scope.analyzerJobs = {};

                _.each(_.keys($scope.analyzers).sort(), function(analyzerId) {
                    $scope.analyzerJobs[analyzerId] = [];
                });                

                angular.forEach($scope.jobs.values, function (job) {
                    if (job.analyzerId in $scope.analyzerJobs) {
                        $scope.analyzerJobs[job.analyzerId].push(job);
                    } else {
                        $scope.analyzerJobs[job.analyzerId] = [job];
                    }
                });

                // Check it a job completed successfully and update the observableId
                if(updates && updates.length > 0) {

                    var statuses = _.pluck(_.map(updates, function(item) {
                        return item.base.details;
                    }), 'status');

                    if(statuses.indexOf('Success') > -1) {
                        CaseArtifactSrv.api().get({
                            'artifactId': observableId
                        }, function (observable) {
                            $scope.artifact = observable;
                        }, function (response) {
                            NotificationSrv.error('artifactDetails', response.data, response.status);
                            CaseTabsSrv.activateTab('observables');
                        });
                    }
                }
            };

            $scope.showReport = function (jobId) {
                $scope.report = {};

                CortexSrv.getJob(jobId).then(function(response) {
                    var job = response.data;
                    $scope.report = {
                        template: job.analyzerId,
                        content: job.report,
                        status: job.status,
                        startDate: job.startDate,
                        endDate: job.endDate
                    };

                    $timeout(function() {
                        var reportEl = angular.element(document.getElementById('analysis-report'))[0];

                        // Scrolling hack using jQuery stuff
                        $('html,body').animate({
                            scrollTop: $(reportEl).offset().top
                        }, 'fast');
                    }, 500);

                }, function(/*err*/) {
                    NotificationSrv.log('An expected error occured while fetching the job report');
                });
            };

            $scope.similarArtifacts = CaseArtifactSrv.api().similar({
                'artifactId': observableId
            });


            $scope.openArtifact = function (a) {
                $state.go('app.case.observables-item', {
                    caseId: a['case'].id,
                    itemId: a.id
                });
            };

            $scope.getLabels = function (selection) {
                var labels = [];

                angular.forEach(selection, function (label) {
                    labels.push(label.text);
                });

                return labels;
            };

            $scope.updateField = function (fieldName, newValue) {
                var field = {};
                field[fieldName] = newValue;

                return CaseArtifactSrv.api().update({
                    artifactId: $scope.artifact.id
                }, field, function (response) {
                    $scope.artifact = response.toJSON();
                }, function (response) {
                    NotificationSrv.error('artifactDetails', response.data, response.status);
                });
            };

            $scope._runAnalyzer = function (serverId, analyzerId, artifactId) {
                return CortexSrv.createJob({
                    cortexId: serverId,
                    artifactId: artifactId,
                    analyzerId: analyzerId
                });
            };

            $scope.runAnalyzer = function (analyzerId) {
                var artifactName = $scope.artifact.data || $scope.artifact.attachment.name;

                CortexSrv.getServers([analyzerId])
                    .then(function (serverId) {
                        return $scope._runAnalyzer(serverId, analyzerId, $scope.artifact.id);
                    })
                    .then(function () {
                        NotificationSrv.log('Analyzer ' + analyzerId + ' has been successfully started for observable: ' + artifactName, 'success');
                    }, function (response) {
                        if (response && response.status) {
                            NotificationSrv.log('Unable to run analyzer ' + analyzerId + ' for observable: ' + artifactName, 'error');
                        }
                    });
            };

            $scope.runAll = function () {
                var artifactId = $scope.artifact.id;
                var artifactName = $scope.artifact.data || $scope.artifact.attachment.name;
                var analyzerIds = _.pluck(_.filter($scope.analyzers, function (a) {
                    return a.active === true;
                }), 'id');

                CortexSrv.getServers(analyzerIds)
                    .then(function (serverId) {
                        return $q.all(_.map(analyzerIds, function (analyzerId) {
                            return $scope._runAnalyzer(serverId, analyzerId, artifactId);
                        }));
                    })
                    .then(function () {
                        NotificationSrv.log('Analyzers has been successfully started for observable: ' + artifactName, 'success');
                    });
            };

        }
    );

})();
