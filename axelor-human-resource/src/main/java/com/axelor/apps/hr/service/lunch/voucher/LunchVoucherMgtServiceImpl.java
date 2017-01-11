/**
 * Axelor Business Solutions
 *
 * Copyright (C) 2017 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.hr.service.lunch.voucher;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.service.administration.GeneralService;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.HRConfig;
import com.axelor.apps.hr.db.LunchVoucherAdvance;
import com.axelor.apps.hr.db.LunchVoucherMgt;
import com.axelor.apps.hr.db.LunchVoucherMgtLine;
import com.axelor.apps.hr.db.repo.HRConfigRepository;
import com.axelor.apps.hr.db.repo.LunchVoucherAdvanceRepository;
import com.axelor.apps.hr.db.repo.LunchVoucherMgtRepository;
import com.axelor.apps.hr.service.config.HRConfigService;
import com.axelor.auth.db.User;
import com.axelor.auth.db.repo.UserRepository;
import com.axelor.common.ObjectUtils;
import com.axelor.exception.AxelorException;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

public class LunchVoucherMgtServiceImpl implements LunchVoucherMgtService {
	
	protected UserRepository userRepository;
	
	protected LunchVoucherMgtRepository lunchVoucherMgtRepository;
	
	protected LunchVoucherMgtLineService lunchVoucherMgtLineService;
	
	protected LunchVoucherAdvanceService lunchVoucherAdvanceService;
	
	protected HRConfigService hrConfigService;
	
	@Inject
	public LunchVoucherMgtServiceImpl(UserRepository userRepository, LunchVoucherMgtLineService lunchVoucherMgtLineService, LunchVoucherAdvanceService lunchVoucherAdvanceService,
								      LunchVoucherMgtRepository lunchVoucherMgtRepository, HRConfigService hrConfigService) {
		
		this.userRepository = userRepository;
		this.lunchVoucherMgtLineService = lunchVoucherMgtLineService;
		this.lunchVoucherMgtRepository = lunchVoucherMgtRepository;
		this.lunchVoucherAdvanceService = lunchVoucherAdvanceService;
		this.hrConfigService = hrConfigService;
	}
	
	@Override
	@Transactional
	public void calculate(LunchVoucherMgt lunchVoucherMgt) throws AxelorException {
		Company company = lunchVoucherMgt.getCompany();
		HRConfig hrConfig = hrConfigService.getHRConfig(company);
		
		List<User> UsersList = userRepository.all().filter("self.activeCompany = ?1", company).fetch();
		
		for (User user : UsersList) {
			Employee employee = user.getEmployee();
			if (employee != null) {
				LunchVoucherMgtLine LunchVoucherMgtLine = lunchVoucherMgtLineService.create(employee, lunchVoucherMgt);
				lunchVoucherMgt.addLunchVoucherMgtLineListItem(LunchVoucherMgtLine);
			}
		}
		
		lunchVoucherMgt.setStatusSelect(LunchVoucherMgtRepository.STATUS_CALCULATED);
		lunchVoucherMgt.setStockQuantityStatus(hrConfig.getAvailableStockLunchVoucher());
		
		calculateTotal(lunchVoucherMgt);
		
		lunchVoucherMgtRepository.save(lunchVoucherMgt);
	}
	
	@Override
	public void calculateTotal(LunchVoucherMgt lunchVoucherMgt) {
		List<LunchVoucherMgtLine> lunchVoucherMgtLineList = lunchVoucherMgt.getLunchVoucherMgtLineList();
		int total = 0;
		int totalInAdvance = 0;
		
		if(!ObjectUtils.isEmpty(lunchVoucherMgtLineList)) {
			for (LunchVoucherMgtLine lunchVoucherMgtLine : lunchVoucherMgtLineList) {
				total += lunchVoucherMgtLine.getLunchVoucherNumber();
				totalInAdvance += lunchVoucherMgtLine.getInAdvanceNbr();
			}
		}
		
		lunchVoucherMgt.setTotalLunchVouchers(total + totalInAdvance + lunchVoucherMgt.getStockLineQuantity());
		lunchVoucherMgt.setRequestedLunchVouchers(total + lunchVoucherMgt.getStockLineQuantity());
	}
	
	@Override
	public int checkStock(Company company, int numberToUse) throws AxelorException {
		
		HRConfig hrConfig = hrConfigService.getHRConfig(company);
		int minStoclLV = hrConfig.getMinStockLunchVoucher();
		int availableStoclLV = hrConfig.getAvailableStockLunchVoucher();
		
		return availableStoclLV - numberToUse - minStoclLV;
	}
	
	@Transactional
	public void export(LunchVoucherMgt lunchVoucherMgt) throws IOException {
		MetaFile metaFile = new MetaFile();
		metaFile.setFileName(I18n.get("Lunch Voucher Mgt") + " - " + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".csv");
		
		Path tempFile = MetaFiles.createTempFile(null, ".csv");
		final OutputStream os = new FileOutputStream(tempFile.toFile());
		
		try(final Writer writer = new OutputStreamWriter(os)) {
			
			List<String> header = new ArrayList<>();
			header.add(escapeCsv(I18n.get("Company code")));
			header.add(escapeCsv(I18n.get("Lunch Voucher's number")));
			header.add(escapeCsv(I18n.get("Employee")));
			header.add(escapeCsv(I18n.get("Lunch Voucher format")));
			
			writer.write(Joiner.on(";").join(header));
			
			for(LunchVoucherMgtLine lunchVoucherMgtLine : lunchVoucherMgt.getLunchVoucherMgtLineList()) {

				List<String> line = new ArrayList<>();
				line.add(escapeCsv(lunchVoucherMgt.getCompany().getCode()));
				line.add(escapeCsv(lunchVoucherMgtLine.getLunchVoucherNumber().toString()));
				line.add(escapeCsv(lunchVoucherMgtLine.getEmployee().getName()));
				line.add(escapeCsv(lunchVoucherMgtLine.getEmployee().getLunchVoucherFormatSelect().toString()));
				
				writer.write("\n");
				writer.write(Joiner.on(";").join(line));
			}
			
			Beans.get(MetaFiles.class).upload(tempFile.toFile(), metaFile);
			
		} catch(Exception e) {
			Throwables.propagate(e);
		} finally {
			Files.deleteIfExists(tempFile);
		}

		lunchVoucherMgt.setCsvFile(metaFile);
		lunchVoucherMgt.setExportDate(Beans.get(GeneralService.class).getTodayDate());
		
		lunchVoucherMgtRepository.save(lunchVoucherMgt);
		
	}
	
	private String escapeCsv(String value) {
		if (value == null) return "";
		if (value.indexOf('"') > -1) value = value.replaceAll("\"", "\"\"");
		return '"' + value + '"';
	}

	@Override
	@Transactional
	public void validate(LunchVoucherMgt lunchVoucherMgt) throws AxelorException {
		Company company = lunchVoucherMgt.getCompany();
		HRConfig hrConfig = hrConfigService.getHRConfig(company);
		
		LunchVoucherAdvanceRepository advanceRepo = Beans.get(LunchVoucherAdvanceRepository.class);
		
		for (LunchVoucherMgtLine item : lunchVoucherMgt.getLunchVoucherMgtLineList()) {
			if(item.getInAdvanceNbr() > 0) {
				
				int qtyToUse = item.getInAdvanceNbr();
				List<LunchVoucherAdvance> list = advanceRepo.all().filter("self.employee.id = ?1 AND self.nbrLunchVouchersUsed < self.nbrLunchVouchers", item.getEmployee().getId()).order("distributionDate").fetch();
				
				for (LunchVoucherAdvance subItem : list) {
					qtyToUse = lunchVoucherAdvanceService.useAdvance(subItem, qtyToUse);
					advanceRepo.save(subItem);
					
					if(qtyToUse <= 0) { break; }
				}
				
			}
		}
		
		hrConfig.setAvailableStockLunchVoucher(hrConfig.getAvailableStockLunchVoucher() + lunchVoucherMgt.getStockLineQuantity());
		lunchVoucherMgt.setStatusSelect(LunchVoucherMgtRepository.STATUS_VALIDATED);
		
		Beans.get(HRConfigRepository.class).save(hrConfig);
		lunchVoucherMgtRepository.save(lunchVoucherMgt);
	}

}
