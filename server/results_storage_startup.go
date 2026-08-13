package main

import (
	"regexp"
	"strings"
)

var persistedResultFilePattern = regexp.MustCompile(`^[0-9]{8}\.jsonl$`)

func initializeResultUpload(dataDir, tokenFile string) (resultUploadAuthorization, error) {
	var authorizer resultUploadAuthorization
	if strings.TrimSpace(tokenFile) != "" {
		loaded, err := loadResultUploadAuthorizer(tokenFile)
		if err != nil {
			return nil, err
		}
		authorizer = loaded
	}
	if err := hardenExistingResultsStorage(dataDir); err != nil {
		return nil, err
	}
	return authorizer, nil
}

func hardenExistingResultsStorage(dataDir string) error {
	return hardenExistingResultsStoragePlatform(dataDir)
}
